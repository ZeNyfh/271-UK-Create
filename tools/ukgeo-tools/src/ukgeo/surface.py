from __future__ import annotations

from pathlib import Path
import math
import re

import geopandas as gpd
import numpy as np
import yaml
from rasterio.enums import MergeAlg
from rasterio.features import rasterize
from rasterio.transform import from_bounds
from rich.console import Console
from tqdm import tqdm

from .bgs import resolve_gpkg
from .manifest import read_manifest, write_manifest
from .raster_memory import U8Raster, replace_nonzero_in_place
from .tiles import write_u8_tile, u8_extension

console = Console()


def make_surface_geology_tiles(*, bgs: Path, rules: Path, manifest_path: Path, out: Path, debug_geotiff: Path | None = None) -> None:
    manifest = read_manifest(manifest_path)
    geo = manifest["georeferencing"]
    world = manifest["world"]
    tile_size = manifest["tile_size"]
    width = world["width"]
    depth = world["depth"]
    transform = from_bounds(
        geo["bng_min_easting"],
        geo["bng_min_northing"],
        geo["bng_max_easting"],
        geo["bng_max_northing"],
        width,
        depth,
    )
    with rules.open("r", encoding="utf-8") as fh:
        config = yaml.safe_load(fh) or {}

    classes = config.get("classes") or {}
    if not classes:
        raise ValueError("surface geology rules must define classes")

    gpkg, tmp = resolve_gpkg(bgs)
    try:
        with U8Raster((depth, width), tmp_parent=out, label="surface") as arr, U8Raster(
            (depth, width), tmp_parent=out, label="surface-burn"
        ) as burned:
            for class_name, class_config in classes.items():
                class_id = int(class_config.get("id", 0))
                if class_id == 0:
                    continue
                shapes = []
                for layer_name in class_config.get("layers", []):
                    try:
                        frame = gpd.read_file(
                            gpkg,
                            layer=layer_name,
                            bbox=(geo["bng_min_easting"], geo["bng_min_northing"], geo["bng_max_easting"], geo["bng_max_northing"]),
                        )
                    except Exception as exc:
                        console.print(f"[yellow]Skipping {layer_name}: {exc}[/yellow]")
                        continue
                    if frame.empty:
                        continue
                    if frame.crs and str(frame.crs).upper() != "EPSG:27700":
                        frame = frame.to_crs("EPSG:27700")
                    fields = class_config.get("fields") or [c for c in frame.columns if c != frame.geometry.name]
                    available_fields = [c for c in fields if c in frame.columns]
                    if available_fields:
                        text = frame[available_fields].fillna("").map(str).agg(" ".join, axis=1).str.lower()
                    else:
                        text = frame.geometry.astype(str).str.lower()
                    keywords = class_config.get("keywords", [])
                    pattern = re.compile("|".join(re.escape(k.lower()) for k in keywords)) if keywords else None
                    mask = text.str.contains(pattern, na=False) if pattern else np.zeros(len(frame), dtype=bool)
                    shapes.extend((geom, class_id) for geom in frame.loc[mask, frame.geometry.name] if geom is not None and not geom.is_empty)
                if shapes:
                    burned[:] = 0
                    rasterize(shapes, out=burned, transform=transform, fill=0, dtype=np.uint8, merge_alg=MergeAlg.replace)
                    replace_nonzero_in_place(arr, burned)
                    console.print(f"{class_name}: burned {len(shapes)} features as id {class_id}")

            remove_isolated_surface_pixels(arr)
            root = out / "geology" / "surface"
            _write_tiles(arr, root, tile_size)
            manifest["surface_geology"] = {
                "path": "geology/surface",
                "extension": u8_extension(),
                "dtype": "uint8",
                "classes": {
                    str(int(cfg.get("id", 0))): {
                        "name": name,
                        "block": cfg.get("block", "minecraft:stone"),
                        "fallback_block": cfg.get("fallback_block", "minecraft:stone"),
                        "color": cfg.get("color", "#777777"),
                    }
                    for name, cfg in classes.items()
                }
            }
            write_manifest(manifest_path, manifest)
            if debug_geotiff:
                _write_debug(debug_geotiff, arr, transform)
    finally:
        if tmp is not None:
            tmp.cleanup()



def remove_isolated_surface_pixels(
    arr: np.ndarray,
    *,
    max_island: int = 3,
    tile_size: int = 4096,
) -> int:
    """Replace tiny class islands with the majority 4-neighbour class.

    Preserves legitimate geological boundaries between larger regions and does
    not blur contiguous deposits. Operates in-place. Returns pixels changed.

    Large disk-backed rasters are cleaned in overlapping tiles so we never build
    world-sized label/roll buffers (those OOMed on the UK extent).
    """
    data = arr
    if getattr(data, "ndim", None) != 2:
        raise ValueError("surface array must be 2D")
    height, width = int(data.shape[0]), int(data.shape[1])
    if height * width <= 8_000_000:
        changed = _remove_isolated_block(np.asarray(data), max_island=max_island)
        if changed and isinstance(arr, np.memmap):
            arr.flush()
        return changed

    overlap = max(1, int(max_island))
    changed = 0
    for z0 in range(0, height, tile_size):
        for x0 in range(0, width, tile_size):
            z1 = min(height, z0 + tile_size)
            x1 = min(width, x0 + tile_size)
            rz0 = max(0, z0 - overlap)
            rx0 = max(0, x0 - overlap)
            rz1 = min(height, z1 + overlap)
            rx1 = min(width, x1 + overlap)
            block = np.array(data[rz0:rz1, rx0:rx1], dtype=np.uint8, copy=True)
            local = _remove_isolated_block(block, max_island=max_island)
            if local == 0:
                continue
            cz0 = z0 - rz0
            cx0 = x0 - rx0
            data[z0:z1, x0:x1] = block[cz0 : cz0 + (z1 - z0), cx0 : cx0 + (x1 - x0)]
            changed += local
    if isinstance(arr, np.memmap):
        arr.flush()
    return changed


def _remove_isolated_block(data: np.ndarray, *, max_island: int) -> int:
    """In-place isolate cleanup for a small/medium dense block."""
    from scipy import ndimage

    if data.ndim != 2:
        raise ValueError("surface block must be 2D")
    structure = ndimage.generate_binary_structure(2, 1)
    changed = 0
    shifts = [
        np.roll(data, 1, axis=0),
        np.roll(data, -1, axis=0),
        np.roll(data, 1, axis=1),
        np.roll(data, -1, axis=1),
    ]
    shifts[0] = shifts[0].copy(); shifts[0][0, :] = 0
    shifts[1] = shifts[1].copy(); shifts[1][-1, :] = 0
    shifts[2] = shifts[2].copy(); shifts[2][:, 0] = 0
    shifts[3] = shifts[3].copy(); shifts[3][:, -1] = 0

    for cls_i in (int(v) for v in np.unique(data)):
        if cls_i == 0:
            continue
        mask = data == cls_i
        if not bool(mask.any()):
            continue
        labels, count = ndimage.label(mask, structure=structure)
        if count == 0:
            continue
        sizes = np.bincount(labels.ravel())
        small = np.flatnonzero((sizes > 0) & (sizes <= max_island))
        small = small[small != 0]
        if small.size == 0:
            continue
        island = np.isin(labels, small)
        votes = np.stack([s[island] for s in shifts], axis=0)
        replacement = np.zeros(votes.shape[1], dtype=np.uint8)
        for i in range(votes.shape[1]):
            vals = votes[:, i]
            vals = vals[vals != cls_i]
            if vals.size == 0:
                replacement[i] = 0
                continue
            uniq, counts = np.unique(vals, return_counts=True)
            replacement[i] = uniq[int(np.argmax(counts * 1000 - uniq))]
        data[island] = replacement
        changed += int(island.sum())
    return changed


def _write_tiles(arr: np.ndarray, root: Path, tile_size: int) -> None:

    for tile_z in tqdm(range(math.ceil(arr.shape[0] / tile_size)), desc="surface geology tile rows"):
        for tile_x in range(math.ceil(arr.shape[1] / tile_size)):
            tile = arr[tile_z * tile_size : (tile_z + 1) * tile_size, tile_x * tile_size : (tile_x + 1) * tile_size]
            if tile.shape != (tile_size, tile_size):
                padded = np.zeros((tile_size, tile_size), dtype=np.uint8)
                padded[: tile.shape[0], : tile.shape[1]] = tile
                tile = padded
            write_u8_tile(root / f"{tile_x:03d}_{tile_z:03d}{u8_extension()}", tile)


def _write_debug(path: Path, arr: np.ndarray, transform) -> None:
    import rasterio

    path.parent.mkdir(parents=True, exist_ok=True)
    with rasterio.open(path, "w", driver="GTiff", height=arr.shape[0], width=arr.shape[1], count=1, dtype="uint8", crs="EPSG:27700", transform=transform) as dst:
        dst.write(arr, 1)
