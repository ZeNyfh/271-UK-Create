from __future__ import annotations

from pathlib import Path
from concurrent.futures import ProcessPoolExecutor, as_completed
import math
import os
import re
import shutil
import tempfile

import geopandas as gpd
import numpy as np
import yaml
from rasterio.features import rasterize
from rasterio.enums import MergeAlg
from rasterio.transform import from_bounds
from rich.console import Console
from tqdm import tqdm

from .bgs import resolve_gpkg
from .manifest import ORE_NAMES, default_u8_layer, read_manifest, write_manifest
from .raster_memory import U8Raster, maximum_in_place
from .tiles import read_u8_tile, write_u8_tile, u8_extension, is_gzip_path

console = Console()


def make_ore_tiles(
    *,
    bgs: Path,
    rules: Path,
    manifest_path: Path,
    out: Path,
    debug_geotiff_dir: Path | None = None,
    jobs: int = 1,
    only_ores: list[str] | None = None,
) -> None:
    manifest = read_manifest(manifest_path)
    geo = manifest["georeferencing"]
    world = manifest["world"]
    tile_size = manifest["tile_size"]
    width = world["width"]
    depth = world["depth"]
    with rules.open("r", encoding="utf-8") as fh:
        config = yaml.safe_load(fh) or {}
    configured_layers = config.get("ores") or {}
    layer_names = list(dict.fromkeys([*ORE_NAMES, *configured_layers.keys()]))
    if only_ores:
        requested = set(only_ores)
        layer_names = [name for name in layer_names if name in requested]
    manifest.setdefault("ore_layers", {})
    for name in layer_names:
        manifest["ore_layers"].setdefault(name, default_u8_layer(f"ores/{name}"))
    jobs = max(1, int(jobs))
    gpkg, tmp = resolve_gpkg(bgs)
    try:
        tasks = [
            (
                ore,
                configured_layers.get(ore, {}),
                str(gpkg),
                geo,
                world,
                width,
                depth,
                tile_size,
                str(out),
                str(debug_geotiff_dir) if debug_geotiff_dir else None,
                jobs == 1,
            )
            for ore in layer_names
        ]
        if jobs == 1 or len(tasks) <= 1:
            for task in tqdm(tasks, desc="ore layers"):
                _print_worker_messages(_make_ore_layer(task))
        else:
            console.print(f"Generating {len(tasks)} ore/mineral layers with {jobs} worker processes.")
            with ProcessPoolExecutor(max_workers=jobs) as executor:
                futures = [executor.submit(_make_ore_layer, task) for task in tasks]
                for future in tqdm(as_completed(futures), total=len(futures), desc="ore layers"):
                    _print_worker_messages(future.result())
        write_manifest(manifest_path, manifest)
    finally:
        if tmp is not None:
            tmp.cleanup()


def _make_ore_layer(task: tuple) -> tuple[str, list[str]]:
    ore, layer_config, gpkg, geo, world, width, depth, tile_size, out, debug_geotiff_dir, show_tile_progress = task
    messages: list[str] = []
    out_path = Path(out)
    transform = from_bounds(
        geo["bng_min_easting"],
        geo["bng_min_northing"],
        geo["bng_max_easting"],
        geo["bng_max_northing"],
        width,
        depth,
    )
    with U8Raster((depth, width), tmp_parent=out_path, label=f"ore-{ore}") as arr, U8Raster(
        (depth, width), tmp_parent=out_path, label=f"ore-{ore}-burn"
    ) as burned:
        for layer_name in layer_config.get("layers", []):
            try:
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
            except Exception as exc:
                messages.append(f"[yellow]Skipping {layer_name}: {exc}[/yellow]")
                continue
            if frame.empty:
                continue
            if frame.crs and str(frame.crs).upper() != "EPSG:27700":
                frame = frame.to_crs("EPSG:27700")
            fields = layer_config.get("fields") or [c for c in frame.columns if c != frame.geometry.name]
            available_fields = [c for c in fields if c in frame.columns]
            if available_fields:
                text = frame[available_fields].fillna("").map(str).agg(" ".join, axis=1).str.lower()
            else:
                text = frame.geometry.astype(str).str.lower()
            shapes = []
            for group in layer_config.get("keyword_groups", []):
                keywords = group.get("keywords", [])
                score = int(group.get("score", layer_config.get("base_score", 80)))
                pattern = re.compile("|".join(re.escape(k.lower()) for k in keywords)) if keywords else None
                mask = text.str.contains(pattern, na=False) if pattern else np.zeros(len(frame), dtype=bool)
                shapes.extend((geom, score) for geom in frame.loc[mask, frame.geometry.name] if geom is not None and not geom.is_empty)
            if shapes:
                merge_alg = MergeAlg.add if str(layer_config.get("merge_alg", "replace")).lower() == "add" else MergeAlg.replace
                burned[:] = 0
                rasterize(
                    shapes,
                    out=burned,
                    transform=transform,
                    fill=0,
                    dtype=np.uint8,
                    merge_alg=merge_alg,
                )
                maximum_in_place(arr, burned)
        _apply_component_exclusions(arr, layer_config, world, ore, messages)
        _write_verified_layer(arr, out_path / "ores" / ore, tile_size, show_progress=show_tile_progress)
        if debug_geotiff_dir:
            _write_debug(Path(debug_geotiff_dir) / f"{ore}.tif", arr, transform)
    return ore, messages


def _print_worker_messages(result: tuple[str, list[str]]) -> None:
    ore, messages = result
    for message in messages:
        console.print(message)
    console.print(f"{ore}: wrote ore score tiles")


def _write_tiles(arr: np.ndarray, root: Path, tile_size: int, *, show_progress: bool = True) -> None:
    rows = range(math.ceil(arr.shape[0] / tile_size))
    if show_progress:
        rows = tqdm(rows, desc=f"{root.name} tile rows")
    for tile_z in rows:
        for tile_x in range(math.ceil(arr.shape[1] / tile_size)):
            tile = arr[tile_z * tile_size : (tile_z + 1) * tile_size, tile_x * tile_size : (tile_x + 1) * tile_size]
            if tile.shape != (tile_size, tile_size):
                padded = np.zeros((tile_size, tile_size), dtype=np.uint8)
                padded[: tile.shape[0], : tile.shape[1]] = tile
                tile = padded
            write_u8_tile(root / f"{tile_x:03d}_{tile_z:03d}{u8_extension()}", tile)


def _write_verified_layer(arr: np.ndarray, root: Path, tile_size: int, *, show_progress: bool = True) -> None:
    parent = root.parent
    parent.mkdir(parents=True, exist_ok=True)
    tmp_root = Path(tempfile.mkdtemp(prefix=f".{root.name}-pending-", dir=parent))
    stale_root = parent / f".{root.name}-stale"
    try:
        _write_tiles(arr, tmp_root, tile_size, show_progress=show_progress)
        _fsync_tree(tmp_root)
        _verify_u8_layer_tiles(tmp_root, tile_size)
        if stale_root.exists():
            shutil.rmtree(stale_root)
        if root.exists():
            root.rename(stale_root)
        tmp_root.rename(root)
        _fsync_directory(parent)
        if stale_root.exists():
            shutil.rmtree(stale_root)
    except Exception:
        shutil.rmtree(tmp_root, ignore_errors=True)
        if not root.exists() and stale_root.exists():
            stale_root.rename(root)
        raise


def _verify_u8_layer_tiles(root: Path, tile_size: int) -> None:
    expected_raw_size = tile_size * tile_size
    extension = u8_extension()
    paths = sorted(root.rglob(f"*{extension}"))
    for path in paths:
        if not is_gzip_path(path) and path.stat().st_size != expected_raw_size:
            raise ValueError(f"{path} size is {path.stat().st_size} bytes, expected {expected_raw_size}")
        arr = read_u8_tile(path, tile_size)
        if arr.shape != (tile_size, tile_size):
            raise ValueError(f"{path} shape is {arr.shape}, expected {(tile_size, tile_size)}")


def _fsync_tree(root: Path) -> None:
    for path in root.rglob("*"):
        if path.is_file():
            fd = os.open(path, os.O_RDONLY)
            try:
                os.fsync(fd)
            finally:
                os.close(fd)
    for path in sorted((p for p in root.rglob("*") if p.is_dir()), reverse=True):
        _fsync_directory(path)
    _fsync_directory(root)


def _fsync_directory(path: Path) -> None:
    fd = os.open(path, os.O_RDONLY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def _write_debug(path: Path, arr: np.ndarray, transform) -> None:
    import rasterio

    path.parent.mkdir(parents=True, exist_ok=True)
    with rasterio.open(path, "w", driver="GTiff", height=arr.shape[0], width=arr.shape[1], count=1, dtype="uint8", crs="EPSG:27700", transform=transform) as dst:
        dst.write(arr, 1)


def _apply_component_exclusions(
    arr: np.ndarray,
    layer_config: dict,
    world: dict,
    ore: str,
    messages: list[str],
) -> None:
    exclusions = layer_config.get("exclude_nearest_components_minecraft") or []
    if not exclusions:
        return
    min_x = int(world["minecraft_min_x"])
    min_z = int(world["minecraft_min_z"])
    width = int(world["width"])
    depth = int(world["depth"])
    for entry in exclusions:
        target_x = int(entry["x"])
        target_z = int(entry["z"])
        search_radius = max(0, int(entry.get("search_radius", 0)))
        cell_x = target_x - min_x
        cell_z = target_z - min_z
        if not (0 <= cell_x < width and 0 <= cell_z < depth):
            messages.append(
                f"[yellow]{ore}: exclusion target ({target_x}, {target_z}) is outside the generated world bounds.[/yellow]"
            )
            continue
        seed = _resolve_exclusion_seed(arr, cell_x, cell_z, search_radius)
        if seed is None:
            messages.append(
                f"[yellow]{ore}: no positive component found within {search_radius} blocks of ({target_x}, {target_z}); nothing removed.[/yellow]"
            )
            continue
        removed = _clear_connected_component(arr, *seed)
        if removed:
            removed_x = seed[0] + min_x
            removed_z = seed[1] + min_z
            messages.append(
                f"{ore}: removed {removed} cells from the component nearest ({target_x}, {target_z}) using seed ({removed_x}, {removed_z})"
            )


def _resolve_exclusion_seed(arr: np.ndarray, cell_x: int, cell_z: int, search_radius: int) -> tuple[int, int] | None:
    if arr[cell_z, cell_x] > 0:
        return cell_x, cell_z
    if search_radius <= 0:
        return None
    z0 = max(0, cell_z - search_radius)
    z1 = min(arr.shape[0], cell_z + search_radius + 1)
    x0 = max(0, cell_x - search_radius)
    x1 = min(arr.shape[1], cell_x + search_radius + 1)
    window = arr[z0:z1, x0:x1]
    ys, xs = np.nonzero(window > 0)
    if len(xs) == 0:
        return None
    distances = (xs + x0 - cell_x) ** 2 + (ys + z0 - cell_z) ** 2
    nearest = int(distances.argmin())
    return int(xs[nearest] + x0), int(ys[nearest] + z0)


def _clear_connected_component(arr: np.ndarray, seed_x: int, seed_z: int) -> int:
    if arr[seed_z, seed_x] == 0:
        return 0
    pending = [(seed_x, seed_z)]
    arr[seed_z, seed_x] = 0
    removed = 0
    while pending:
        x, z = pending.pop()
        removed += 1
        for next_x, next_z in ((x - 1, z), (x + 1, z), (x, z - 1), (x, z + 1)):
            if 0 <= next_x < arr.shape[1] and 0 <= next_z < arr.shape[0] and arr[next_z, next_x] > 0:
                arr[next_z, next_x] = 0
                pending.append((next_x, next_z))
    return removed
