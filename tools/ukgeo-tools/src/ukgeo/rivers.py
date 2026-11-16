from __future__ import annotations

from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path
from collections import defaultdict, deque
import math
from typing import Any

import geopandas as gpd
import numpy as np
from rasterio.enums import MergeAlg
from rasterio.features import rasterize
from rasterio.transform import from_bounds
from rasterio.windows import Window
from rasterio.windows import transform as window_transform
from rich.console import Console
from shapely.geometry import LineString, MultiLineString
from shapely.ops import linemerge
from tqdm import tqdm

from .bgs import resolve_gpkg
from .manifest import read_manifest, write_manifest
from .raster_memory import U8Raster, find_memmap_path, maximum_in_place, row_windows
from .river_stations import BANK_CARVE_REACH, STATION_BAKE_WORKERS, _ForkWorkers, _env_workers, bake_river_stations_standalone
from .tiles import (
    HEIGHT_NODATA,
    layer_uses_regions,
    read_layer_tile,
    read_r16_tile,
    region_metadata,
    river_u8_layer,
    write_region_file_from_tiles,
    write_u8_tile,
    u8_extension,
    r16_extension,
)

console = Console()

MAX_RIVER_HALFWIDTH = 60  # room for ORN order-7 pre-output bases (~52–60) before /6 → 10
# Floor before RIVER_OUTPUT_WIDTH_FACTOR. OpenRiverNetwork (ORN) Strahler on GB
# runs order 1–7 (order-1 headwaters ≈47% of network length; max 7 on
# Severn/Clyde/Ouse/Great Ouse). Survival finals at axis_scale 1.0 after the
# output factor: 1→1, 2→1, 3→2, 4→3, 5→5, 6→7, 7→10.
MIN_RIVER_HALFWIDTH_BY_ORDER = {1: 1, 2: 1, 3: 2}
SOURCE_ORDER_FIELDS = ("ORDER_", "ORDER", "US_ORDER", "STRAHLER", "STRAHLER_ORDER", "STREAM_ORDER")
SOURCE_DATASET_FIELDS = ("source_dataset", "SOURCE_DATASET")
SOURCE_START_NODE_FIELDS = ("start_node", "START_NODE", "source_start_node", "FROM_NODE", "from_node")
SOURCE_END_NODE_FIELDS = ("end_node", "END_NODE", "source_end_node", "TO_NODE", "to_node")
SOURCE_FLOW_DIRECTION_FIELDS = ("flow_direction", "FLOW_DIRECTION", "direction")
# IE/NI use the same Strahler→half-width pipeline as GB (no regional thinning).
THINNER_RIVER_DATASETS: set[str] = set()
THINNER_RIVER_WIDTH_FACTOR = 1.0
GB_RIVER_DATASETS = {"os_open_rivers_gb"}
# Kept at 0 so ORN order 2–3 stay thin brooks/streams (was +2 fattening).
GB_SMALL_STREAM_HALF_WIDTH_BONUS = 0
# Mild arterial boost for ORN order ≥6 only (Severn-scale channels).
GB_MAJOR_RIVER_WIDTH_FACTOR = 1.15
TOP_ORDER_THINNING_FACTORS = (0.5, 1.0 / 1.5, 1.0 / 1.25, 1.0 / 1.125)
RIVER_OUTPUT_WIDTH_FACTOR = 1.0 / 6.0
RIVER_RASTER_BATCH_SIZE = 25000
# Fork workers for edge rasterization. Override with RIVER_RASTER_WORKERS.
# Each worker burns one tile (typically 512²) and never a world-sized array.
RIVER_RASTER_WORKERS = 12
_RIVER_RASTER_MIN_EDGES = 100
PREVIEW_RIVER_WIDTH_SCALE = 0.18
PREVIEW_RIVER_MAX_RADIUS = 6
RIVER_MEMMAP_LABELS = (
    "rivers",
    "river-order",
    "river-half-width",
    "river-preview-radius",
    "river-burn",
)
# Fork workers. Override with DISTANCE_BAKE_WORKERS. Each worker caches 16 source tiles.
DISTANCE_BAKE_WORKERS = 12
_DISTANCE_TILE_CACHE_MAX = 16


def _read_river_frame(rivers: Path, manifest: dict[str, Any], *, layer: str | None):
    geo = manifest["georeferencing"]
    gpkg, tmp = resolve_gpkg(rivers)
    try:
        layer_name = layer or _default_layer(gpkg)
        frame = gpd.read_file(
            gpkg,
            layer=layer_name,
            bbox=(
                geo["bng_min_easting"],
                geo["bng_min_northing"],
                geo["bng_max_easting"],
                geo["bng_max_northing"],
            ),
        )
    except Exception:
        if tmp is not None:
            tmp.cleanup()
        raise
    return frame, tmp


def _prepare_river_frame(frame: gpd.GeoDataFrame) -> gpd.GeoDataFrame:
    if frame.empty or not frame.crs:
        return frame
    if str(frame.crs).upper() != "EPSG:27700":
        return frame.to_crs("EPSG:27700")
    return frame


def river_edges_from_frame(frame: gpd.GeoDataFrame, manifest: dict[str, Any], root: Path) -> list[_Edge]:
    if frame.empty:
        return []
    return _strahler_widths(_extract_lines(frame), manifest, root).edges


def load_river_edges(rivers: Path, manifest_path: Path, *, layer: str | None = None) -> list[_Edge]:
    """Directed edges from the rivers GPKG, matching make_river_tiles."""
    manifest = read_manifest(manifest_path)
    frame, tmp = _read_river_frame(rivers, manifest, layer=layer)
    try:
        frame = _prepare_river_frame(frame)
        if frame.empty:
            console.print("[yellow]No river features intersect the manifest extent.[/yellow]")
            return []
        return river_edges_from_frame(frame, manifest, manifest_path.parent)
    finally:
        if tmp is not None:
            tmp.cleanup()


def make_river_tiles(
    *,
    rivers: Path,
    manifest_path: Path,
    out: Path,
    layer: str | None = None,
    width_metres: float = 30.0,
    debug_geotiff: Path | None = None,
    resume_memmaps: bool = False,
    skip_edges: int = 0,
) -> None:
    if skip_edges < 0:
        raise ValueError("--skip-edges must be >= 0")
    if skip_edges and not resume_memmaps:
        raise ValueError("--skip-edges requires --resume-memmaps")
    if resume_memmaps and skip_edges % RIVER_RASTER_BATCH_SIZE != 0:
        raise ValueError(
            f"--skip-edges must be a multiple of {RIVER_RASTER_BATCH_SIZE} "
            f"(last flushed batch boundary); got {skip_edges}"
        )
    manifest = read_manifest(manifest_path)
    geo = manifest["georeferencing"]
    world = manifest["world"]
    tile_size = int(manifest["tile_size"])
    width = int(world["width"])
    depth = int(world["depth"])
    transform = from_bounds(
        geo["bng_min_easting"],
        geo["bng_min_northing"],
        geo["bng_max_easting"],
        geo["bng_max_northing"],
        width,
        depth,
    )
    resume_paths = _resolve_resume_paths(out) if resume_memmaps else {}
    if resume_memmaps:
        console.print(
            f"[cyan]Resuming river memmaps under {out}; skipping first {skip_edges} edges[/cyan]"
        )
    frame, tmp = _read_river_frame(rivers, manifest, layer=layer)
    try:
        if frame.empty:
            console.print("[yellow]No river features intersect the manifest extent.[/yellow]")
        frame = _prepare_river_frame(frame)
        vector_edges = river_edges_from_frame(frame, manifest, manifest_path.parent)
        with (
            U8Raster((depth, width), tmp_parent=out, label="rivers", resume_path=resume_paths.get("rivers")) as arr,
            U8Raster(
                (depth, width), tmp_parent=out, label="river-order", resume_path=resume_paths.get("river-order")
            ) as order_arr,
            U8Raster(
                (depth, width),
                tmp_parent=out,
                label="river-half-width",
                resume_path=resume_paths.get("river-half-width"),
            ) as half_width_arr,
            U8Raster(
                (depth, width),
                tmp_parent=out,
                label="river-preview-radius",
                resume_path=resume_paths.get("river-preview-radius"),
            ) as preview_radius_arr,
        ):
            if not frame.empty:
                # Fall back to the old fixed-width raster if graph extraction produced no usable line edges.
                fallback_fixed = not vector_edges
                cell_metres = _cell_metres(geo, width, depth)
                axis_scale = _horizontal_axis_scale(manifest)
                if fallback_fixed:
                    if skip_edges:
                        raise ValueError("--skip-edges is not supported for the fixed-width river fallback path")
                    cover_shapes: list[tuple[object, int]] = []
                    width_shapes: list[tuple[object, int]] = []
                    order_shapes: list[tuple[object, int]] = []
                    preview_shapes: list[tuple[object, int]] = []
                    scaled_width_metres = width_metres * axis_scale * RIVER_OUTPUT_WIDTH_FACTOR
                    fallback_half_width = (
                        max(1, int(round(scaled_width_metres / (2.0 * cell_metres)))) if scaled_width_metres > 0 else 1
                    )
                    for geom in tqdm(frame.geometry, desc="rasterizing fallback rivers"):
                        if geom is None or geom.is_empty:
                            continue
                        if not isinstance(geom, (LineString, MultiLineString)):
                            continue
                        if scaled_width_metres > 0:
                            shape = geom.buffer(scaled_width_metres / 2.0, cap_style="round", join_style="round")
                        else:
                            shape = geom
                        cover_shapes.append((shape, 255))
                        width_shapes.append((shape, fallback_half_width))
                        order_shapes.append((shape, 1))
                        preview_shapes.append((shape, _preview_radius_for_order(1)))
                        if len(cover_shapes) >= RIVER_RASTER_BATCH_SIZE:
                            _rasterize_shape_batches_direct(cover_shapes, arr, transform)
                            _rasterize_shape_batches_direct(width_shapes, half_width_arr, transform)
                            _rasterize_shape_batches_direct(order_shapes, order_arr, transform)
                            _rasterize_shape_batches_direct(preview_shapes, preview_radius_arr, transform)
                            cover_shapes.clear()
                            width_shapes.clear()
                            order_shapes.clear()
                            preview_shapes.clear()
                    _rasterize_shape_batches_direct(cover_shapes, arr, transform)
                    _rasterize_shape_batches_direct(width_shapes, half_width_arr, transform)
                    _rasterize_shape_batches_direct(order_shapes, order_arr, transform)
                    _rasterize_shape_batches_direct(preview_shapes, preview_radius_arr, transform)
                else:
                    edges = vector_edges
                    if skip_edges > len(edges):
                        raise ValueError(f"--skip-edges {skip_edges} exceeds edge count {len(edges)}")
                    remaining_edges = edges[skip_edges:]
                    _rasterize_edge_pass(
                        remaining_edges,
                        arr,
                        transform,
                        cell_metres,
                        tile_size=tile_size,
                        value_fn=lambda edge: 255,
                        desc="rasterizing river coverage",
                        initial=skip_edges,
                        total=len(edges),
                    )
                    _rasterize_edge_pass(
                        sorted(remaining_edges, key=lambda edge: min(255, max(1, edge.order))),
                        order_arr,
                        transform,
                        cell_metres,
                        tile_size=tile_size,
                        value_fn=lambda edge: min(255, max(1, edge.order)),
                        desc="rasterizing river order",
                    )
                    _rasterize_edge_pass(
                        sorted(remaining_edges, key=lambda edge: min(255, max(1, edge.half_width))),
                        half_width_arr,
                        transform,
                        cell_metres,
                        tile_size=tile_size,
                        value_fn=lambda edge: min(255, max(1, edge.half_width)),
                        desc="rasterizing river half width",
                    )
                    _rasterize_edge_pass(
                        sorted(remaining_edges, key=_preview_radius_for_edge),
                        preview_radius_arr,
                        transform,
                        cell_metres,
                        tile_size=tile_size,
                        value_fn=_preview_radius_for_edge,
                        desc="rasterizing river preview radius",
                    )
            root = out / "water" / "rivers"
            _write_tiles(arr, root, tile_size)
            _write_tiles(order_arr, out / "water" / "river_order", tile_size)
            _write_tiles(half_width_arr, out / "water" / "river_half_width", tile_size)
            _write_tiles(preview_radius_arr, out / "water" / "river_preview_radius", tile_size)
            max_half_width = _u8_max(half_width_arr) if half_width_arr.size else 0
            manifest["rivers"] = {
                "path": "water/rivers",
                "extension": u8_extension(),
                "dtype": "uint8",
                "min": 0,
                "max": 255,
                "order_path": "water/river_order",
                "half_width_path": "water/river_half_width",
                "preview_radius_path": "water/river_preview_radius",
                "max_half_width": max_half_width,
                "note": "255 marks cells inside variable-width river/watercourse vectors. Widths follow OpenRiverNetwork Strahler order 1–7 survival half-widths (order 1–2 thin brooks; 3 creek; 4–5 small/mid; 6–7 large/arterial), from source ORDER_ where present or computed Strahler otherwise, then scaled by RIVER_OUTPUT_WIDTH_FACTOR for in-world readability. Runtime no longer floors channels with river_widen_radius.",
            }
            write_manifest(manifest_path, manifest)
            if debug_geotiff:
                _write_debug(debug_geotiff, arr, transform)
            bake_river_distance_grids(out, manifest_path=manifest_path)
        # Memmaps are closed before station stamps. The mask is re-read from river tiles.
        if vector_edges:
            bake_river_stations_standalone(
                out,
                manifest_path=manifest_path,
                edges=vector_edges,
                default_workers=STATION_BAKE_WORKERS,
            )
        elif not frame.empty:
            raise ValueError("river station bake requires vector edges; refusing centroid fallback")
    finally:
        if tmp is not None:
            tmp.cleanup()


# Set in the parent before fork. Workers read this; it is not pickled per task.
_DISTANCE_BAKE: dict[str, Any] | None = None


def _distance_read_tile(layer_name: str, tile_x: int, tile_z: int) -> np.ndarray:
    ctx = _DISTANCE_BAKE
    if ctx is None:
        raise RuntimeError("river distance bake worker missing fork context")
    tile_size = int(ctx["tile_size"])
    tiles_x = int(ctx["tiles_x"])
    tiles_z = int(ctx["tiles_z"])
    if tile_x < 0 or tile_z < 0 or tile_x >= tiles_x or tile_z >= tiles_z:
        return np.zeros((tile_size, tile_size), dtype=np.uint8)
    return _distance_read_tile_cached(layer_name, tile_x, tile_z)


@lru_cache(maxsize=_DISTANCE_TILE_CACHE_MAX)
def _distance_read_tile_cached(layer_name: str, tile_x: int, tile_z: int) -> np.ndarray:
    ctx = _DISTANCE_BAKE
    if ctx is None:
        raise RuntimeError("river distance bake worker missing fork context")
    tile_size = int(ctx["tile_size"])
    layer = ctx["river_layer"] if layer_name == "rivers" else ctx["half_layer"]
    return np.array(read_layer_tile(ctx["root"], layer, tile_x, tile_z, tile_size), dtype=np.uint8, copy=True)


def _distance_halo_stack(layer_name: str, tile_x: int, tile_z: int) -> np.ndarray:
    ctx = _DISTANCE_BAKE
    if ctx is None:
        raise RuntimeError("river distance bake worker missing fork context")
    tile_size = int(ctx["tile_size"])
    halo = int(ctx["halo"])
    tiles_x = int(ctx["tiles_x"])
    tiles_z = int(ctx["tiles_z"])
    size = tile_size + halo * 2
    out = np.zeros((size, size), dtype=np.uint8)
    world_x0 = tile_x * tile_size - halo
    world_z0 = tile_z * tile_size - halo
    world_x1 = world_x0 + size
    world_z1 = world_z0 + size
    src_tx0 = max(0, world_x0 // tile_size)
    src_tz0 = max(0, world_z0 // tile_size)
    src_tx1 = min(tiles_x - 1, (world_x1 - 1) // tile_size)
    src_tz1 = min(tiles_z - 1, (world_z1 - 1) // tile_size)
    for src_tz in range(src_tz0, src_tz1 + 1):
        for src_tx in range(src_tx0, src_tx1 + 1):
            tile = _distance_read_tile(layer_name, src_tx, src_tz)
            tx0 = src_tx * tile_size
            tz0 = src_tz * tile_size
            ix0 = max(world_x0, tx0)
            iz0 = max(world_z0, tz0)
            ix1 = min(world_x1, tx0 + tile_size)
            iz1 = min(world_z1, tz0 + tile_size)
            if ix0 >= ix1 or iz0 >= iz1:
                continue
            out_z0 = iz0 - world_z0
            out_x0 = ix0 - world_x0
            tile_z0 = iz0 - tz0
            tile_x0 = ix0 - tx0
            out[out_z0 : out_z0 + (iz1 - iz0), out_x0 : out_x0 + (ix1 - ix0)] = tile[
                tile_z0 : tile_z0 + (iz1 - iz0), tile_x0 : tile_x0 + (ix1 - ix0)
            ]
    return out


def _encode_distance_tile(tile_x: int, tile_z: int) -> tuple[np.ndarray, np.ndarray]:
    from scipy.ndimage import distance_transform_edt

    ctx = _DISTANCE_BAKE
    if ctx is None:
        raise RuntimeError("river distance bake worker missing fork context")
    tile_size = int(ctx["tile_size"])
    halo = int(ctx["halo"])
    search_radius = int(ctx["search_radius"])
    dist_tile = np.full((tile_size, tile_size), 255, dtype=np.uint8)
    hw_tile = np.zeros((tile_size, tile_size), dtype=np.uint8)
    center = _distance_read_tile("rivers", tile_x, tile_z)
    if not np.any(center):
        tile_radius = (halo + tile_size - 1) // tile_size
        has_nearby = False
        for dz in range(-tile_radius, tile_radius + 1):
            for dx in range(-tile_radius, tile_radius + 1):
                if dx == 0 and dz == 0:
                    continue
                if np.any(_distance_read_tile("rivers", tile_x + dx, tile_z + dz)):
                    has_nearby = True
                    break
            if has_nearby:
                break
        if not has_nearby:
            return dist_tile, hw_tile
    mask_halo = _distance_halo_stack("rivers", tile_x, tile_z) > 0
    if not np.any(mask_halo):
        return dist_tile, hw_tile
    half_halo = _distance_halo_stack("half_width", tile_x, tile_z)
    distances, indices = distance_transform_edt(~mask_halo, return_indices=True)
    center_dist = distances[halo : halo + tile_size, halo : halo + tile_size]
    nearest_z = indices[0][halo : halo + tile_size, halo : halo + tile_size]
    nearest_x = indices[1][halo : halo + tile_size, halo : halo + tile_size]
    nearest_hw = half_halo[nearest_z, nearest_x]
    within = center_dist <= search_radius
    codes = np.minimum(254, np.rint(center_dist * 2.0).astype(np.int16)).astype(np.uint8)
    dist_tile = np.where(within, codes, np.uint8(255)).astype(np.uint8)
    hw_tile = np.where(within, nearest_hw, np.uint8(0)).astype(np.uint8)
    return dist_tile, hw_tile


def _bake_one_distance_tile(task: tuple[int, int]) -> None:
    tile_x, tile_z = task
    dist_tile, hw_tile = _encode_distance_tile(tile_x, tile_z)
    ctx = _DISTANCE_BAKE
    if ctx is None:
        raise RuntimeError("river distance bake worker missing fork context")
    tile_size = int(ctx["tile_size"])
    extension = ctx["extension"]
    write_u8_tile(
        ctx["dist_root"] / f"{tile_x:03d}_{tile_z:03d}{extension}",
        dist_tile,
        tile_size,
        sparse_fill=255,
    )
    write_u8_tile(ctx["hw_root"] / f"{tile_x:03d}_{tile_z:03d}{extension}", hw_tile, tile_size)


def _encode_distance_region_slot(
    task: tuple[int, int, int],
) -> tuple[int, np.ndarray | None, np.ndarray | None]:
    index, tile_x, tile_z = task
    dist_tile, hw_tile = _encode_distance_tile(tile_x, tile_z)
    dist_out = None if np.all(dist_tile == 255) else dist_tile
    hw_out = None if not np.any(hw_tile) else hw_tile
    return index, dist_out, hw_out


def _iter_distance_tiles(tiles_x: int, tiles_z: int):
    for tile_z in range(tiles_z):
        for tile_x in range(tiles_x):
            yield (tile_x, tile_z)


def _source_region_file(root: Path, river_layer: dict[str, Any], region_x: int, region_z: int) -> Path:
    region_path = str(river_layer.get("region_path", f"{river_layer['path']}/regions"))
    region_ext = str(river_layer.get("region_extension", ".u8rg"))
    return root / region_path / f"{region_x:03d}_{region_z:03d}{region_ext}"


def bake_river_distance_grids(root: Path, *, manifest_path: Path | None = None) -> None:
    """Offline-bake river distance + nearest-half-width u8 layers for O(1) runtime samples."""
    global _DISTANCE_BAKE
    manifest_file = manifest_path or (root / "manifest.json")
    manifest = read_manifest(manifest_file)
    rivers = manifest.get("rivers")
    if not rivers:
        console.print("[yellow]No rivers layer in manifest; skipping distance bake.[/yellow]")
        return
    if "half_width_path" not in rivers:
        console.print("[yellow]No river half_width_path; skipping distance bake.[/yellow]")
        return

    tile_size = int(manifest["tile_size"])
    padded_width = int(manifest["world"]["padded_width"])
    padded_depth = int(manifest["world"]["padded_depth"])
    tiles_x = math.ceil(padded_width / tile_size)
    tiles_z = math.ceil(padded_depth / tile_size)
    max_half_width = int(rivers.get("max_half_width", 0))
    search_radius = max(max_half_width, 1) + BANK_CARVE_REACH
    halo = search_radius

    river_layer = river_u8_layer(rivers)
    half_layer = river_u8_layer(rivers, "half_width_path", "half_width")
    use_regions = layer_uses_regions(river_layer)
    region_tiles = int(rivers.get("region_tiles", river_layer.get("region_tiles", 8)))

    distance_path = "water/river_distance"
    nearest_hw_path = "water/river_nearest_half_width"
    extension = rivers.get("extension", u8_extension())
    dist_root = root / distance_path
    hw_root = root / nearest_hw_path

    _DISTANCE_BAKE = {
        "root": root,
        "tile_size": tile_size,
        "tiles_x": tiles_x,
        "tiles_z": tiles_z,
        "halo": halo,
        "search_radius": search_radius,
        "river_layer": river_layer,
        "half_layer": half_layer,
        "extension": extension,
        "dist_root": dist_root,
        "hw_root": hw_root,
    }
    _distance_read_tile_cached.cache_clear()
    total_tiles = tiles_x * tiles_z
    processes = min(_env_workers("DISTANCE_BAKE_WORKERS", DISTANCE_BAKE_WORKERS), max(1, total_tiles))
    if processes > 1:
        console.print(
            f"[cyan]River distance tiles[/cyan] workers={processes} cache={_DISTANCE_TILE_CACHE_MAX}"
        )
    try:
        with _ForkWorkers(processes, enabled=processes > 1) as pool:
            if use_regions:
                regions_x = math.ceil(tiles_x / region_tiles)
                regions_z = math.ceil(tiles_z / region_tiles)
                dist_region_root = root / distance_path / "regions"
                hw_region_root = root / nearest_hw_path / "regions"
                dist_region_root.mkdir(parents=True, exist_ok=True)
                hw_region_root.mkdir(parents=True, exist_ok=True)
                slot_count = region_tiles * region_tiles
                for region_z in tqdm(range(regions_z), desc="river distance regions"):
                    for region_x in range(regions_x):
                        dist_out = dist_region_root / f"{region_x:03d}_{region_z:03d}.u8rg"
                        hw_out = hw_region_root / f"{region_x:03d}_{region_z:03d}.u8rg"
                        if not _source_region_file(root, river_layer, region_x, region_z).exists():
                            dist_out.unlink(missing_ok=True)
                            hw_out.unlink(missing_ok=True)
                            continue
                        tasks: list[tuple[int, int, int]] = []
                        for local_z in range(region_tiles):
                            for local_x in range(region_tiles):
                                tile_x = region_x * region_tiles + local_x
                                tile_z = region_z * region_tiles + local_z
                                if tile_x >= tiles_x or tile_z >= tiles_z:
                                    continue
                                tasks.append((local_z * region_tiles + local_x, tile_x, tile_z))
                        dist_tiles: list[np.ndarray | None] = [None] * slot_count
                        hw_tiles: list[np.ndarray | None] = [None] * slot_count
                        for index, dist_tile, hw_tile in pool.imap(
                            _encode_distance_region_slot,
                            tasks,
                            task_count=len(tasks),
                        ):
                            dist_tiles[index] = dist_tile
                            hw_tiles[index] = hw_tile
                        write_region_file_from_tiles(
                            dist_out,
                            tiles=dist_tiles,
                            tile_size=tile_size,
                            region_tiles=region_tiles,
                            dtype="uint8",
                            missing_tile=255,
                        )
                        write_region_file_from_tiles(
                            hw_out,
                            tiles=hw_tiles,
                            tile_size=tile_size,
                            region_tiles=region_tiles,
                            dtype="uint8",
                            missing_tile=0,
                        )
            else:
                dist_root.mkdir(parents=True, exist_ok=True)
                hw_root.mkdir(parents=True, exist_ok=True)
                for _ in pool.imap(
                    _bake_one_distance_tile,
                    _iter_distance_tiles(tiles_x, tiles_z),
                    task_count=total_tiles,
                    desc="river distance tiles",
                ):
                    pass
        rivers["distance_path"] = distance_path
        rivers["nearest_half_width_path"] = nearest_hw_path
        rivers["distance_search_radius"] = int(search_radius)
        rivers["distance_units"] = "half_blocks"
        if use_regions:
            for key, value in region_metadata(distance_path, "uint8", region_tiles=region_tiles, missing_tile=255).items():
                rivers[f"distance_{key}"] = value
            for key, value in region_metadata(nearest_hw_path, "uint8", region_tiles=region_tiles, missing_tile=0).items():
                rivers[f"nearest_half_width_{key}"] = value
        write_manifest(manifest_file, manifest)
        console.print(
            f"[green]Baked river distance grids[/green] search_radius={search_radius} "
            f"storage={'regions' if use_regions else 'tiles'}"
        )
    finally:
        _DISTANCE_BAKE = None
        _distance_read_tile_cached.cache_clear()


def _resolve_resume_paths(out: Path) -> dict[str, Path]:
    return {label: find_memmap_path(out, label) for label in RIVER_MEMMAP_LABELS}


def _default_layer(gpkg: Path) -> str:
    import fiona

    layers = fiona.listlayers(gpkg)
    if "watercourse_link" in layers:
        return "watercourse_link"
    for layer in layers:
        if "watercourse" in layer.lower() or "river" in layer.lower():
            return layer
    return layers[0]


def _write_tiles(arr: np.ndarray, root: Path, tile_size: int) -> None:
    for tile_z in tqdm(range(math.ceil(arr.shape[0] / tile_size)), desc="river tile rows"):
        for tile_x in range(math.ceil(arr.shape[1] / tile_size)):
            tile = arr[tile_z * tile_size : (tile_z + 1) * tile_size, tile_x * tile_size : (tile_x + 1) * tile_size]
            if tile.shape != (tile_size, tile_size):
                padded = np.zeros((tile_size, tile_size), dtype=np.uint8)
                padded[: tile.shape[0], : tile.shape[1]] = tile
                tile = padded
            write_u8_tile(root / f"{tile_x:03d}_{tile_z:03d}{u8_extension()}", tile)


def _u8_max(arr: np.ndarray) -> int:
    maximum = 0
    for window in row_windows(arr.shape[0]):
        maximum = max(maximum, int(arr[window].max()))
    return maximum


def _zero_u8(arr: np.ndarray) -> None:
    for window in row_windows(arr.shape[0]):
        arr[window] = 0
    if isinstance(arr, np.memmap):
        arr.flush()


def _rasterize_shape_batches(
    shapes: list[tuple[object, int]],
    out: np.ndarray,
    transform,
    burned: np.ndarray,
) -> None:
    if not shapes:
        return
    _zero_u8(burned)
    rasterize(
        shapes,
        out=burned,
        transform=transform,
        fill=0,
        dtype=np.uint8,
        merge_alg=MergeAlg.replace,
        all_touched=True,
    )
    maximum_in_place(out, burned)


def _river_raster_workers() -> int:
    return _env_workers("RIVER_RASTER_WORKERS", RIVER_RASTER_WORKERS)


def _river_raster_chunksize(task_count: int) -> int:
    if task_count >= 2048:
        return 16
    if task_count >= 256:
        return 8
    return 4


def _inverse_affine_coeffs(transform) -> tuple[float, float, float, float, float, float]:
    inverse = ~transform
    return (
        float(inverse.a),
        float(inverse.b),
        float(inverse.c),
        float(inverse.d),
        float(inverse.e),
        float(inverse.f),
    )


def _tile_span_for_bounds(
    minx: float,
    miny: float,
    maxx: float,
    maxy: float,
    coeffs: tuple[float, float, float, float, float, float],
    tile_size: int,
    width: int,
    depth: int,
) -> tuple[int, int, int, int] | None:
    """Cell-space tile span of a world AABB.

    Corner mapping matches ``rasterio.windows.from_bounds`` on the full-world
    affine. One extra cell is included so ``all_touched`` pixels on the
    boundary still fall in a visited tile.
    """
    if not (math.isfinite(minx) and math.isfinite(miny) and math.isfinite(maxx) and math.isfinite(maxy)):
        return None
    if maxx < minx:
        minx, maxx = maxx, minx
    if maxy < miny:
        miny, maxy = maxy, miny
    ia, ib, ic, id_, ie, if_ = coeffs
    corners = ((minx, maxy), (maxx, maxy), (maxx, miny), (minx, miny))
    cols = [ia * x + ib * y + ic for x, y in corners]
    rows = [id_ * x + ie * y + if_ for x, y in corners]
    col_start, col_stop = min(cols), max(cols)
    row_start, row_stop = min(rows), max(rows)
    col0 = max(0, int(math.floor(col_start)) - 1)
    row0 = max(0, int(math.floor(row_start)) - 1)
    col1 = min(width, int(math.ceil(col_stop)) + 1)
    row1 = min(depth, int(math.ceil(row_stop)) + 1)
    if col0 >= col1 or row0 >= row1:
        return None
    tx0 = col0 // tile_size
    tz0 = row0 // tile_size
    tx1 = (col1 - 1) // tile_size
    tz1 = (row1 - 1) // tile_size
    return tx0, tx1, tz0, tz1


def _tiles_touched_by_edge(
    edge: _Edge,
    half_width_cells: int,
    transform,
    cell_metres: float,
    tile_size: int,
    width: int,
    depth: int,
    coeffs: tuple[float, float, float, float, float, float] | None = None,
) -> set[tuple[int, int]]:
    line = edge.line
    if line is None or line.is_empty:
        return set()
    coords = list(line.coords)
    if len(coords) < 2:
        return set()
    if coeffs is None:
        coeffs = _inverse_affine_coeffs(transform)
    expand = max(1, int(half_width_cells)) * float(cell_metres)
    expand += max(abs(float(transform.a)), abs(float(transform.e)), 0.0)
    tiles: set[tuple[int, int]] = set()
    for start, end in zip(coords, coords[1:]):
        x0, y0 = float(start[0]), float(start[1])
        x1, y1 = float(end[0]), float(end[1])
        if not (math.isfinite(x0) and math.isfinite(y0) and math.isfinite(x1) and math.isfinite(y1)):
            continue
        span = _tile_span_for_bounds(
            min(x0, x1) - expand,
            min(y0, y1) - expand,
            max(x0, x1) + expand,
            max(y0, y1) + expand,
            coeffs,
            tile_size,
            width,
            depth,
        )
        if span is None:
            continue
        tx0, tx1, tz0, tz1 = span
        for tile_z in range(tz0, tz1 + 1):
            for tile_x in range(tx0, tx1 + 1):
                tiles.add((tile_x, tile_z))
    return tiles


def _edge_raster_arrays(edges: list[_Edge], value_fn) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    count = len(edges)
    orders = np.empty(count, dtype=np.int32)
    half_widths = np.empty(count, dtype=np.int32)
    values = np.empty(count, dtype=np.int32)
    for index, edge in enumerate(edges):
        orders[index] = int(edge.order)
        half_widths[index] = max(1, int(edge.half_width))
        values[index] = int(value_fn(edge))
    return orders, half_widths, values


def _bucket_river_edge_tiles(
    edges: list[_Edge],
    half_widths: np.ndarray,
    transform,
    cell_metres: float,
    tile_size: int,
    width: int,
    depth: int,
    *,
    desc: str,
) -> dict[tuple[int, int], list[int]]:
    buckets: dict[tuple[int, int], list[int]] = defaultdict(list)
    coeffs = _inverse_affine_coeffs(transform)
    iterator: Any = edges
    if len(edges) >= 1000:
        iterator = tqdm(edges, desc=f"{desc} index", leave=False)
    for index, edge in enumerate(iterator):
        touched = _tiles_touched_by_edge(
            edge,
            int(half_widths[index]),
            transform,
            cell_metres,
            tile_size,
            width,
            depth,
            coeffs,
        )
        for key in touched:
            buckets[key].append(index)
    return buckets


def _tile_raster_transform(transform, col0: int, row0: int, cols: int, rows: int):
    """Windowed affine so this tile's pixels match the full-world transform."""
    return window_transform(Window(col_off=col0, row_off=row0, width=cols, height=rows), transform)


def _maximum_tile_into(out: np.ndarray, tile_x: int, tile_z: int, tile: np.ndarray, tile_size: int) -> None:
    depth, width = out.shape
    row0 = tile_z * tile_size
    col0 = tile_x * tile_size
    if row0 < 0 or col0 < 0 or row0 >= depth or col0 >= width:
        return
    rows = min(tile_size, depth - row0, int(tile.shape[0]))
    cols = min(tile_size, width - col0, int(tile.shape[1]))
    if rows <= 0 or cols <= 0:
        return
    dest = out[row0 : row0 + rows, col0 : col0 + cols]
    np.maximum(dest, tile[:rows, :cols], out=dest)


# Set in the parent before fork. Workers read edges and precomputed values
# from here so tasks stay small and value_fn is never pickled.
_RIVER_EDGE_RASTER: dict[str, Any] | None = None


def _rasterize_one_edge_tile(task: tuple[int, int, list[int]]) -> tuple[int, int, np.ndarray] | None:
    tile_x, tile_z, indices = task
    ctx = _RIVER_EDGE_RASTER
    if ctx is None:
        raise RuntimeError("river raster worker missing fork context")
    edges: list[_Edge] = ctx["edges"]
    half_widths: np.ndarray = ctx["half_widths"]
    values: np.ndarray = ctx["values"]
    transform = ctx["transform"]
    cell_metres = float(ctx["cell_metres"])
    tile_size = int(ctx["tile_size"])
    width = int(ctx["width"])
    depth = int(ctx["depth"])
    row0 = tile_z * tile_size
    col0 = tile_x * tile_size
    if row0 < 0 or col0 < 0 or row0 >= depth or col0 >= width:
        return None
    rows = min(tile_size, depth - row0)
    cols = min(tile_size, width - col0)
    if rows <= 0 or cols <= 0:
        return None
    shapes: list[tuple[int, object]] = []
    for index in indices:
        edge = edges[index]
        line = edge.line
        if line is None or line.is_empty:
            continue
        buffered = line.buffer(int(half_widths[index]) * cell_metres, cap_style="round", join_style="round")
        if buffered.is_empty:
            continue
        shapes.append((int(values[index]), buffered))
    if not shapes:
        return None
    # Ascending value + MergeAlg.replace makes the higher burn win inside the tile.
    shapes.sort(key=lambda item: item[0])
    burned = [(geom, value) for value, geom in shapes]
    tile = np.zeros((rows, cols), dtype=np.uint8)
    rasterize(
        burned,
        out=tile,
        transform=_tile_raster_transform(transform, col0, row0, cols, rows),
        dtype=np.uint8,
        merge_alg=MergeAlg.replace,
        all_touched=True,
    )
    if not tile.any():
        return None
    return tile_x, tile_z, tile


def _rasterize_edge_pass_sequential(
    edges: list[_Edge],
    out: np.ndarray,
    transform,
    cell_metres: float,
    *,
    value_fn,
    desc: str,
    initial: int = 0,
    total: int | None = None,
) -> None:
    shapes: list[tuple[object, int]] = []
    progress_total = total if total is not None else len(edges)
    for edge in tqdm(edges, desc=desc, initial=initial, total=progress_total):
        if edge.line.is_empty:
            continue
        half_width = max(1, edge.half_width)
        buffer_metres = half_width * cell_metres
        buffered = edge.line.buffer(buffer_metres, cap_style="round", join_style="round")
        shapes.append((buffered, int(value_fn(edge))))
        if len(shapes) >= RIVER_RASTER_BATCH_SIZE:
            _rasterize_shape_batches_direct(shapes, out, transform)
            shapes.clear()
    _rasterize_shape_batches_direct(shapes, out, transform)


def _rasterize_edge_pass_parallel(
    edges: list[_Edge],
    out: np.ndarray,
    transform,
    cell_metres: float,
    *,
    tile_size: int,
    value_fn,
    desc: str,
    workers: int,
    initial: int = 0,
    total: int | None = None,
) -> None:
    """Rasterize edges per tile and merge with ``np.maximum``.

    Empty tiles are skipped. A single tile, or ``workers <= 1``, falls back
    to the sequential pass. ``initial`` and ``total`` apply only to that fallback.
    """
    if tile_size <= 0:
        raise ValueError(f"tile_size must be positive, got {tile_size}")
    depth, width = (int(out.shape[0]), int(out.shape[1]))
    if depth <= 0 or width <= 0 or not edges:
        return
    orders, half_widths, values = _edge_raster_arrays(edges, value_fn)
    buckets = _bucket_river_edge_tiles(
        edges,
        half_widths,
        transform,
        cell_metres,
        tile_size,
        width,
        depth,
        desc=desc,
    )
    if not buckets:
        return
    tasks = [(tile_x, tile_z, indices) for (tile_x, tile_z), indices in buckets.items()]
    buckets.clear()
    worker_count = min(max(1, int(workers)), len(tasks))
    if worker_count <= 1:
        _rasterize_edge_pass_sequential(
            edges,
            out,
            transform,
            cell_metres,
            value_fn=value_fn,
            desc=desc,
            initial=initial,
            total=total,
        )
        return
    global _RIVER_EDGE_RASTER
    _RIVER_EDGE_RASTER = {
        "edges": edges,
        "orders": orders,
        "half_widths": half_widths,
        "values": values,
        "transform": transform,
        "cell_metres": float(cell_metres),
        "tile_size": int(tile_size),
        "width": width,
        "depth": depth,
    }
    try:
        # _ForkWorkers uses multiprocessing.get_context("fork") so the edge
        # list stays shared copy-on-write. Tasks carry only tile coordinates
        # and edge indices.
        with _ForkWorkers(worker_count, enabled=True) as pool:
            for result in pool.imap(
                _rasterize_one_edge_tile,
                tasks,
                task_count=len(tasks),
                desc=desc,
                chunksize=_river_raster_chunksize(len(tasks)),
            ):
                if result is None:
                    continue
                tile_x, tile_z, tile = result
                _maximum_tile_into(out, tile_x, tile_z, tile, tile_size)
    finally:
        _RIVER_EDGE_RASTER = None
    if isinstance(out, np.memmap):
        out.flush()


def _rasterize_edge_pass(
    edges: list[_Edge],
    out: np.ndarray,
    transform,
    cell_metres: float,
    *,
    value_fn,
    desc: str,
    tile_size: int = 512,
    initial: int = 0,
    total: int | None = None,
    workers: int | None = None,
) -> None:
    """Burn buffered edges into ``out``.

    Fewer than 100 edges, or a single worker, keeps the sequential full-raster
    path. Otherwise tiles are burned in forked workers and merged with maximum.
    """
    configured = _river_raster_workers() if workers is None else max(1, int(workers))
    if len(edges) < _RIVER_RASTER_MIN_EDGES or configured <= 1:
        _rasterize_edge_pass_sequential(
            edges,
            out,
            transform,
            cell_metres,
            value_fn=value_fn,
            desc=desc,
            initial=initial,
            total=total,
        )
        return
    _rasterize_edge_pass_parallel(
        edges,
        out,
        transform,
        cell_metres,
        tile_size=tile_size,
        value_fn=value_fn,
        desc=desc,
        workers=configured,
        initial=initial,
        total=total,
    )


def _rasterize_shape_batches_direct(
    shapes: list[tuple[object, int]],
    out: np.ndarray,
    transform,
) -> None:
    if not shapes:
        return
    rasterize(
        shapes,
        out=out,
        transform=transform,
        dtype=np.uint8,
        merge_alg=MergeAlg.replace,
        all_touched=True,
    )


def _write_debug(path: Path, arr: np.ndarray, transform) -> None:
    import rasterio

    path.parent.mkdir(parents=True, exist_ok=True)
    with rasterio.open(path, "w", driver="GTiff", height=arr.shape[0], width=arr.shape[1], count=1, dtype="uint8", crs="EPSG:27700", transform=transform) as dst:
        dst.write(arr, 1)


@dataclass(frozen=True)
class _InputLine:
    line: LineString
    source_order: int | None = None
    source_dataset: str | None = None
    source_start_node: str | None = None
    source_end_node: str | None = None
    source_flow_direction: str | None = None


def _source_column(columns: list[str], candidates: tuple[str, ...]) -> str | None:
    lookup = {column.upper(): column for column in columns}
    for candidate in candidates:
        found = lookup.get(candidate.upper())
        if found is not None:
            return found
    return None


def _source_order_column(columns: list[str]) -> str | None:
    return _source_column(columns, SOURCE_ORDER_FIELDS)


def _source_dataset_column(columns: list[str]) -> str | None:
    return _source_column(columns, SOURCE_DATASET_FIELDS)


def _source_start_node_column(columns: list[str]) -> str | None:
    return _source_column(columns, SOURCE_START_NODE_FIELDS)


def _source_end_node_column(columns: list[str]) -> str | None:
    return _source_column(columns, SOURCE_END_NODE_FIELDS)


def _source_flow_direction_column(columns: list[str]) -> str | None:
    return _source_column(columns, SOURCE_FLOW_DIRECTION_FIELDS)


def _coerce_source_order(value) -> int | None:
    if value is None:
        return None
    try:
        if np.isnan(value):
            return None
    except TypeError:
        pass
    try:
        order = int(value)
    except (TypeError, ValueError):
        return None
    return order if order > 0 else None


def _coerce_optional_text(value) -> str | None:
    if value is None:
        return None
    try:
        if np.isnan(value):
            return None
    except TypeError:
        pass
    text = str(value).strip()
    if not text or text.lower() == "nan":
        return None
    return text


def _extract_lines(frame: gpd.GeoDataFrame) -> list[_InputLine]:
    columns = list(frame.columns)
    order_column = _source_order_column(columns)
    source_dataset_column = _source_dataset_column(columns)
    source_start_node_column = _source_start_node_column(columns)
    source_end_node_column = _source_end_node_column(columns)
    source_flow_direction_column = _source_flow_direction_column(columns)
    order_values = frame[order_column] if order_column is not None else None
    source_dataset_values = frame[source_dataset_column] if source_dataset_column is not None else None
    source_start_node_values = frame[source_start_node_column] if source_start_node_column is not None else None
    source_end_node_values = frame[source_end_node_column] if source_end_node_column is not None else None
    source_flow_direction_values = frame[source_flow_direction_column] if source_flow_direction_column is not None else None
    lines: list[_InputLine] = []
    for geom, raw_order, raw_source_dataset, raw_source_start_node, raw_source_end_node, raw_source_flow_direction in zip(
        frame.geometry,
        order_values if order_values is not None else [None] * len(frame),
        source_dataset_values if source_dataset_values is not None else [None] * len(frame),
        source_start_node_values if source_start_node_values is not None else [None] * len(frame),
        source_end_node_values if source_end_node_values is not None else [None] * len(frame),
        source_flow_direction_values if source_flow_direction_values is not None else [None] * len(frame),
        strict=False,
    ):
        source_order = _coerce_source_order(raw_order)
        source_dataset = _coerce_optional_text(raw_source_dataset)
        source_start_node = _coerce_optional_text(raw_source_start_node)
        source_end_node = _coerce_optional_text(raw_source_end_node)
        source_flow_direction = _coerce_optional_text(raw_source_flow_direction)
        if geom is None or geom.is_empty:
            continue
        if isinstance(geom, LineString):
            if len(geom.coords) >= 2:
                lines.append(_InputLine(geom, source_order, source_dataset, source_start_node, source_end_node, source_flow_direction))
        elif isinstance(geom, MultiLineString):
            merged = linemerge(geom)
            if isinstance(merged, LineString) and len(merged.coords) >= 2 and source_start_node and source_end_node:
                lines.append(_InputLine(merged, source_order, source_dataset, source_start_node, source_end_node, source_flow_direction))
                continue
            for part in geom.geoms:
                if len(part.coords) >= 2:
                    lines.append(_InputLine(part, source_order, source_dataset, None, None, source_flow_direction))
    return lines


def _cell_metres(geo: dict, width: int, depth: int) -> float:
    x = (float(geo["bng_max_easting"]) - float(geo["bng_min_easting"])) / max(1, width)
    z = (float(geo["bng_max_northing"]) - float(geo["bng_min_northing"])) / max(1, depth)
    return (abs(x) + abs(z)) * 0.5


def _horizontal_axis_scale(manifest: dict) -> float:
    """Return the mean |axis_scale| for x/z, floored at 1.0.

    At axis_scale 2 the raster cells are half as wide in metres, so order-based
    half-widths (in cells) must be doubled to keep rivers the same physical and
    relative size as a 1x world.
    """
    axis = manifest.get("axis_scale") or {}
    try:
        scale_x = abs(float(axis.get("x", 1.0) or 1.0))
        scale_z = abs(float(axis.get("z", 1.0) or 1.0))
    except (TypeError, ValueError):
        return 1.0
    return max(1.0, (scale_x + scale_z) * 0.5)


class _HeightSampler:
    def __init__(self, manifest: dict, root: Path):
        self.manifest = manifest
        self.root = root
        self.tile_size = int(manifest["tile_size"])
        self.geo = manifest["georeferencing"]
        self.world = manifest["world"]
        self.cache: dict[tuple[int, int], np.ndarray] = {}

    def sample(self, easting: float, northing: float) -> float | None:
        width = int(self.world["width"])
        depth = int(self.world["depth"])
        data_x = int(math.floor((easting - float(self.geo["bng_min_easting"])) * width / (float(self.geo["bng_max_easting"]) - float(self.geo["bng_min_easting"]))))
        data_z = int(math.floor((float(self.geo["bng_max_northing"]) - northing) * depth / (float(self.geo["bng_max_northing"]) - float(self.geo["bng_min_northing"]))))
        if data_x < 0 or data_z < 0 or data_x >= int(self.world["padded_width"]) or data_z >= int(self.world["padded_depth"]):
            return None
        tile_x = data_x // self.tile_size
        tile_z = data_z // self.tile_size
        tile = self.cache.get((tile_x, tile_z))
        if tile is None:
            path = self.root / self.manifest["height"]["path"] / f"{tile_x:03d}_{tile_z:03d}{self.manifest['height'].get('extension', r16_extension())}"
            if not path.exists():
                return None
            tile = read_r16_tile(path, self.tile_size)
            self.cache[(tile_x, tile_z)] = tile
        value = int(tile[data_z % self.tile_size, data_x % self.tile_size])
        return None if value == HEIGHT_NODATA else value / 10.0


class _Edge:
    def __init__(self, line: LineString, order: int, half_width: int, source_dataset: str | None = None, source_order_provided: bool = False):
        self.line = line
        self.order = order
        self.half_width = half_width
        self.source_dataset = source_dataset
        self.source_order_provided = source_order_provided


class _StrahlerResult:
    def __init__(self, edges: list[_Edge]):
        self.edges = edges


def _strahler_widths(lines: list[_InputLine], manifest: dict, root: Path) -> _StrahlerResult:
    if not lines:
        return _StrahlerResult([])
    # Keep each source dataset as its own network. Irish rivers ship with ORDER_
    # while GB must compute Strahler; mixing them made GB orders compress to 1
    # against the Irish source_max / inflated computed_max.
    by_dataset: dict[str | None, list[_InputLine]] = defaultdict(list)
    for item in lines:
        by_dataset[item.source_dataset].append(item)
    edges: list[_Edge] = []
    axis_scale = _horizontal_axis_scale(manifest)
    for dataset_lines in by_dataset.values():
        edges.extend(_strahler_widths_for_network(dataset_lines, manifest, root).edges)
    _apply_axis_scale_to_half_widths(edges, axis_scale)
    _apply_gb_width_adjustments(edges, axis_scale)
    _apply_output_width_scale(edges)
    return _StrahlerResult(edges)


def _strahler_widths_for_network(lines: list[_InputLine], manifest: dict, root: Path) -> _StrahlerResult:
    if not lines:
        return _StrahlerResult([])
    sampler = _HeightSampler(manifest, root)
    source_max_order = max((item.source_order or 0) for item in lines)
    node_ids: dict[tuple[str, str] | tuple[int, int], int] = {}
    node_points: list[tuple[float, float]] = []

    def node_id(point: tuple[float, float], source_node: str | None = None) -> int:
        key: tuple[str, str] | tuple[int, int]
        if source_node is not None:
            key = ("source-node", source_node)
        else:
            key = (round(point[0]), round(point[1]))
        found = node_ids.get(key)
        if found is not None:
            return found
        found = len(node_points)
        node_ids[key] = found
        node_points.append((float(point[0]), float(point[1])))
        return found

    raw_edges: list[tuple[int, int, LineString, int | None, str | None, str | None]] = []
    for item in lines:
        coords = list(item.line.coords)
        a = node_id((coords[0][0], coords[0][1]), item.source_start_node)
        b = node_id((coords[-1][0], coords[-1][1]), item.source_end_node)
        if a != b:
            raw_edges.append(
                (a, b, item.line, item.source_order, item.source_dataset, item.source_flow_direction)
            )
    if not raw_edges:
        return _StrahlerResult([])

    heights = [sampler.sample(e, n) for e, n in node_points]
    downstream: dict[int, list[tuple[int, int]]] = defaultdict(list)
    upstream_count: dict[int, int] = defaultdict(int)
    oriented: list[tuple[int, int, LineString, int | None, str | None]] = []
    # Iterate raw_edges only — never zip with `lines`. Degenerate a==b skips would
    # shift that pairing and apply the wrong flow_direction to later edges.
    for a, b, line, source_order, source_dataset, flow_direction in raw_edges:
        src, dst = _orient_edge(a, b, flow_direction, heights, node_points)
        downstream[src].append((dst, len(oriented)))
        upstream_count[dst] += 1
        upstream_count.setdefault(src, upstream_count.get(src, 0))
        oriented.append((src, dst, line, source_order, source_dataset))

    node_order = [1] * len(node_points)
    incoming_orders: list[list[int]] = [[] for _ in node_points]
    ready = deque(sorted(node for node in range(len(node_points)) if upstream_count.get(node, 0) == 0))
    remaining = dict(upstream_count)
    processed_edges: set[int] = set()

    def combine(orders: list[int]) -> int:
        if not orders:
            return 1
        highest = max(orders)
        return highest + 1 if sum(1 for order in orders if order == highest) >= 2 else highest

    while ready:
        node = ready.popleft()
        node_order[node] = max(node_order[node], combine(incoming_orders[node]))
        for dst, edge_index in downstream.get(node, []):
            processed_edges.add(edge_index)
            incoming_orders[dst].append(node_order[node])
            remaining[dst] = remaining.get(dst, 0) - 1
            if remaining[dst] == 0:
                ready.append(dst)

    # Cycles/braids are collapsed deterministically by processing high-to-low elevation and carrying max incoming order.
    unresolved = [i for i in range(len(oriented)) if i not in processed_edges]
    for edge_index in sorted(unresolved, key=lambda i: (-(heights[oriented[i][0]] or -9999.0), oriented[i][0], oriented[i][1])):
        src, dst, _, _, _ = oriented[edge_index]
        node_order[src] = max(node_order[src], combine(incoming_orders[src]))
        incoming_orders[dst].append(node_order[src])
        node_order[dst] = max(node_order[dst], combine(incoming_orders[dst]))

    computed_max_order = max((node_order[src] for src, _, _, source_order, _ in oriented if source_order is None), default=0)
    edges = []
    for src, dst, line, source_order, source_dataset in oriented:
        computed_order = max(1, node_order[src])
        computed_order = _normalize_computed_order_to_source_scale(computed_order, computed_max_order, source_max_order)
        order = source_order if source_order is not None else computed_order
        base_half_width = _half_width_for_order(order)
        half_width = _scaled_half_width_for_order(order, base_half_width)
        e0 = heights[src]
        e1 = heights[dst]
        if e0 is not None and e1 is not None:
            slope = abs(e0 - e1) / max(1.0, line.length)
            if slope > 0.018:
                half_width = max(1, round(half_width * 0.75))
            elif slope < 0.003 and min(e0, e1) < 90.0:
                half_width = round(half_width * 1.2)
        minimum = _minimum_half_width_for_order(order)
        clamped_half_width = min(MAX_RIVER_HALFWIDTH, max(minimum, half_width))
        edges.append(
            _Edge(
                line,
                min(order, 255),
                _dataset_half_width(clamped_half_width, source_dataset),
                source_dataset,
                source_order is not None,
            )
        )
    _thin_top_order_half_widths(edges)
    return _StrahlerResult(edges)


def _apply_axis_scale_to_half_widths(edges: list[_Edge], axis_scale: float) -> None:
    if axis_scale <= 1.0 or not edges:
        return
    max_half_width = max(1, int(round(MAX_RIVER_HALFWIDTH * axis_scale)))
    for edge in edges:
        scaled = max(1, int(round(edge.half_width * axis_scale)))
        edge.half_width = min(max_half_width, scaled)


def _apply_output_width_scale(edges: list[_Edge]) -> None:
    if not edges:
        return
    for edge in edges:
        edge.half_width = max(1, int(round(edge.half_width * RIVER_OUTPUT_WIDTH_FACTOR)))


def _thin_top_order_half_widths(edges: list[_Edge]) -> None:
    thin_edges = [edge for edge in edges if edge.source_dataset in THINNER_RIVER_DATASETS]
    if not thin_edges:
        return
    top_orders = sorted({edge.order for edge in thin_edges if edge.order > 0}, reverse=True)[
        : len(TOP_ORDER_THINNING_FACTORS)
    ]
    factors = {order: factor for order, factor in zip(top_orders, TOP_ORDER_THINNING_FACTORS, strict=False)}
    for edge in thin_edges:
        factor = factors.get(edge.order)
        if factor is None:
            continue
        edge.half_width = max(1, int(round(edge.half_width * factor)))


def _apply_gb_width_adjustments(edges: list[_Edge], axis_scale: float) -> None:
    if not edges:
        return
    max_half_width = max(1, int(round(MAX_RIVER_HALFWIDTH * max(1.0, axis_scale))))
    for edge in edges:
        if edge.source_dataset not in GB_RIVER_DATASETS:
            continue
        # Order 1–5 stay data-driven (bonus is 0; no major factor).
        if edge.order <= 3 and GB_SMALL_STREAM_HALF_WIDTH_BONUS:
            edge.half_width = min(max_half_width, edge.half_width + GB_SMALL_STREAM_HALF_WIDTH_BONUS)
            continue
        if edge.order < 6:
            continue
        edge.half_width = min(
            max_half_width,
            max(1, int(round(edge.half_width * GB_MAJOR_RIVER_WIDTH_FACTOR))),
        )


def _normalize_computed_order_to_source_scale(order: int, computed_max_order: int, source_max_order: int) -> int:
    if order <= 0:
        return 1
    if source_max_order <= 0 or computed_max_order <= 0 or computed_max_order <= source_max_order:
        return order
    scaled = int(round(order * source_max_order / computed_max_order))
    return max(1, min(source_max_order, scaled))


def _dataset_half_width(half_width: int, source_dataset: str | None) -> int:
    # Kept for API/tests; thinning set is empty so IE/NI match GB mainland widths.
    if not THINNER_RIVER_DATASETS or source_dataset not in THINNER_RIVER_DATASETS:
        return half_width
    return max(1, int(math.floor(half_width * THINNER_RIVER_WIDTH_FACTOR)))


def _preview_radius_for_half_width(half_width: int) -> int:
    if half_width <= 0:
        return 0
    return max(1, min(PREVIEW_RIVER_MAX_RADIUS, int(round(half_width * PREVIEW_RIVER_WIDTH_SCALE))))


def _preview_radius_for_order(order: int) -> int:
    if order <= 0:
        return 0
    if order in {1, 2}:
        return 1
    if order == 3:
        return 2
    if order == 4:
        return 3
    if order == 5:
        return 5
    return 6


def _irish_preview_radius_for_order(order: int) -> int:
    if order <= 2:
        return 0
    if order == 3:
        return 1
    if order == 4:
        return 2
    if order == 5:
        return 3
    if order == 6:
        return 4
    return 5


def _preview_radius_for_edge(edge: _Edge) -> int:
    # Same preview rules for GB / IE / NI — order ladder when source order missing,
    # otherwise half-width scaled preview.
    if not edge.source_order_provided:
        return _preview_radius_for_order(edge.order)
    return _preview_radius_for_half_width(edge.half_width)


def _orient_edge(
    a: int,
    b: int,
    source_flow_direction: str | None,
    heights: list[float | None],
    node_points: list[tuple[float, float]],
) -> tuple[int, int]:
    flow = (source_flow_direction or "").strip().lower()
    if flow:
        if "against" in flow:
            return b, a
        if "in direction" in flow or "with" in flow:
            return a, b
    ha = heights[a]
    hb = heights[b]
    if ha is not None and hb is not None and ha != hb:
        return (a, b) if ha > hb else (b, a)
    na = node_points[a][1]
    nb = node_points[b][1]
    return (a, b) if (na > nb or (na == nb and a < b)) else (b, a)


def _scaled_half_width_for_order(order: int, base_half_width: int) -> int:
    scaled = math.ceil(base_half_width * _width_multiplier_for_order(order))
    return max(_minimum_half_width_for_order(order), scaled)


def _width_multiplier_for_order(_order: int) -> float:
    # Bases already encode ORN survival scale; do not fatten headwaters.
    return 1.0


def _minimum_half_width_for_order(order: int) -> int:
    if order <= 1:
        return MIN_RIVER_HALFWIDTH_BY_ORDER[1]
    if order == 2:
        return MIN_RIVER_HALFWIDTH_BY_ORDER[2]
    if order == 3:
        return MIN_RIVER_HALFWIDTH_BY_ORDER[3]
    return 1


def _half_width_for_order(order: int) -> int:
    # Pre-RIVER_OUTPUT_WIDTH_FACTOR (~6×) so finals match ORN survival half-widths
    # at axis_scale 1.0: 1→1, 2→1, 3→2, 4→3, 5→5, 6→7, 7→10. Orders ≥6 are set
    # slightly under target so GB_MAJOR_RIVER_WIDTH_FACTOR (1.15) lands on it.
    if order <= 1:
        return 6
    if order == 2:
        return 6
    if order == 3:
        return 12
    if order == 4:
        return 18
    if order == 5:
        return 30
    if order == 6:
        return 36
    return 52 + min(18, (order - 7) * 6)
