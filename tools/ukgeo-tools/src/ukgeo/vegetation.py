from __future__ import annotations

from pathlib import Path
from concurrent.futures import ProcessPoolExecutor
import math
import zipfile

import numpy as np
import rasterio
from rasterio.enums import Resampling
from rasterio.transform import from_bounds
from rasterio.warp import reproject
from rich.console import Console
from tqdm import tqdm

from .manifest import read_manifest, write_manifest
from .tiles import write_u8_tile

console = Console()


VEGETATION_CLASSES: dict[int, dict[str, str]] = {
    0: {"name": "none/ocean", "color": "#101820"},
    1: {"name": "broadleaf woodland", "color": "#1f6f3a"},
    2: {"name": "conifer woodland", "color": "#174c34"},
    3: {"name": "arable and horticulture", "color": "#d9c66a"},
    4: {"name": "improved grassland", "color": "#78b957"},
    5: {"name": "neutral grassland", "color": "#9ccc66"},
    6: {"name": "calcareous grassland", "color": "#c8d78a"},
    7: {"name": "acid grassland", "color": "#8aa84f"},
    8: {"name": "wetland/bog/fen", "color": "#4c8f8a"},
    9: {"name": "heath/heather", "color": "#8c5a99"},
    10: {"name": "freshwater", "color": "#3b82c4"},
    11: {"name": "urban/suburban", "color": "#777777"},
    12: {"name": "rocky", "color": "#b9b0a2"},
}

LCM_TO_VEGETATION = np.zeros(256, dtype=np.uint8)
LCM_TO_VEGETATION[1] = 1
LCM_TO_VEGETATION[2] = 2
LCM_TO_VEGETATION[3] = 3
LCM_TO_VEGETATION[4] = 4
LCM_TO_VEGETATION[5] = 5
LCM_TO_VEGETATION[6] = 6
LCM_TO_VEGETATION[7] = 7
LCM_TO_VEGETATION[[8, 11, 19]] = 8
LCM_TO_VEGETATION[[9, 10]] = 9
LCM_TO_VEGETATION[14] = 10
LCM_TO_VEGETATION[[20, 21]] = 11
LCM_TO_VEGETATION[[12, 15, 16, 17, 18]] = 12


def make_vegetation_tiles(
    *,
    landcover: Path,
    manifest_path: Path,
    out: Path,
    band: int = 1,
    cell_metres: float = 50.0,
    debug_geotiff: Path | None = None,
    jobs: int = 1,
) -> None:
    manifest = read_manifest(manifest_path)
    geo = manifest["georeferencing"]
    world = manifest["world"]
    tile_size = int(manifest["tile_size"])
    width = int(world["width"])
    depth = int(world["depth"])
    cell_blocks = cell_blocks_for_metres(cell_metres, geo, width, depth)
    width_cells = math.ceil(width / cell_blocks)
    depth_cells = math.ceil(depth / cell_blocks)
    padded_width_cells = math.ceil(int(world["padded_width"]) / cell_blocks)
    padded_depth_cells = math.ceil(int(world["padded_depth"]) / cell_blocks)
    tiles_x = math.ceil(padded_width_cells / tile_size)
    tiles_z = math.ceil(padded_depth_cells / tile_size)
    raster_path = _resolve_raster_path(landcover)
    root = out / "vegetation"
    root.mkdir(parents=True, exist_ok=True)
    jobs = max(1, int(jobs))

    with rasterio.open(raster_path) as src:
        if band < 1 or band > src.count:
            raise ValueError(f"Band {band} is outside raster band range 1..{src.count}")
        if src.crs is None:
            raise ValueError("Land cover raster has no CRS")
        console.print(f"Reading land cover raster {raster_path}")
        console.print(f"source CRS={src.crs}, size={src.width}x{src.height}, band={band}")

    console.print(f"Vegetation cell size: {cell_metres:.0f} m (~{cell_blocks} blocks/cell)")
    tasks = [
        (
            raster_path,
            band,
            geo,
            width,
            depth,
            cell_blocks,
            width_cells,
            depth_cells,
            tile_size,
            tiles_x,
            str(root),
            tile_z,
        )
        for tile_z in range(tiles_z)
    ]
    if jobs == 1 or len(tasks) <= 1:
        for tile_z in tqdm(range(tiles_z), desc="vegetation tile rows"):
            _write_vegetation_tile_row(tasks[tile_z])
    else:
        console.print(f"Generating vegetation tile rows with {jobs} worker processes.")
        with ProcessPoolExecutor(max_workers=jobs) as executor:
            for _ in tqdm(executor.map(_write_vegetation_tile_row, tasks), total=len(tasks), desc="vegetation tile rows"):
                pass

    manifest["vegetation"] = {
        "path": "vegetation",
        "extension": ".u8.gz",
        "dtype": "uint8",
        "cell_blocks": cell_blocks,
        "cell_metres": cell_metres,
        "width_cells": width_cells,
        "depth_cells": depth_cells,
        "source": str(landcover),
        "source_band": band,
        "classes": {str(class_id): meta for class_id, meta in VEGETATION_CLASSES.items()},
        "source_classes": {
            "1": "Broadleaved woodland",
            "2": "Coniferous woodland",
            "3": "Arable and horticulture",
            "4": "Improved grassland",
            "5": "Neutral grassland",
            "6": "Calcareous grassland",
            "7": "Acid grassland",
            "8": "Fen, marsh and swamp",
            "9": "Heather",
            "10": "Heather grassland",
            "11": "Bog",
            "12": "Inland rock",
            "13": "Saltwater",
            "14": "Freshwater",
            "15": "Supralittoral rock",
            "16": "Supralittoral sediment",
            "17": "Littoral rock",
            "18": "Littoral sediment",
            "19": "Saltmarsh",
            "20": "Urban",
            "21": "Suburban",
        },
    }
    write_manifest(manifest_path, manifest)
    if debug_geotiff:
        _write_debug_geotiff(debug_geotiff, out / "vegetation", manifest, tiles_x, tiles_z, tile_size, width_cells, depth_cells)


def cell_blocks_for_metres(cell_metres: float, geo: dict, width: int, depth: int) -> int:
    metres_x, metres_z = block_metres_scale(geo, width, depth)
    metres_per_block = (metres_x + metres_z) / 2.0
    return max(1, int(round(cell_metres / metres_per_block)))


def block_metres_scale(geo: dict, width: int, depth: int) -> tuple[float, float]:
    min_e = float(geo["bng_min_easting"])
    min_n = float(geo["bng_min_northing"])
    max_e = float(geo["bng_max_easting"])
    max_n = float(geo["bng_max_northing"])
    return (max_e - min_e) / width, (max_n - min_n) / depth


def resample_blocks_to_cells(block_data: np.ndarray, cell_blocks: int) -> np.ndarray:
    if cell_blocks <= 1:
        return block_data.astype(np.uint8, copy=False)
    height, width = block_data.shape
    cells_z = math.ceil(height / cell_blocks)
    cells_x = math.ceil(width / cell_blocks)
    cells = np.zeros((cells_z, cells_x), dtype=np.uint8)
    for cell_z in range(cells_z):
        z0 = cell_z * cell_blocks
        z1 = min(height, z0 + cell_blocks)
        for cell_x in range(cells_x):
            x0 = cell_x * cell_blocks
            x1 = min(width, x0 + cell_blocks)
            patch = block_data[z0:z1, x0:x1]
            if patch.size == 0:
                continue
            counts = np.bincount(patch.ravel(), minlength=256)
            cells[cell_z, cell_x] = np.uint8(counts.argmax())
    return cells


def _write_vegetation_tile_row(task: tuple) -> int:
    raster_path, band, geo, width, depth, cell_blocks, width_cells, depth_cells, tile_size, tiles_x, root, tile_z = task
    root_path = Path(root)
    with rasterio.open(raster_path) as src:
        for tile_x in range(tiles_x):
            tile = np.zeros((tile_size, tile_size), dtype=np.uint8)
            cell_x0 = tile_x * tile_size
            cell_z0 = tile_z * tile_size
            valid_w = max(0, min(tile_size, width_cells - cell_x0))
            valid_h = max(0, min(tile_size, depth_cells - cell_z0))
            if valid_w > 0 and valid_h > 0:
                block_x0 = cell_x0 * cell_blocks
                block_z0 = cell_z0 * cell_blocks
                block_w = min(valid_w * cell_blocks, width - block_x0)
                block_h = min(valid_h * cell_blocks, depth - block_z0)
                raw_blocks = np.zeros((block_h, block_w), dtype=np.uint8)
                dst_transform = _window_transform(geo, width, depth, block_x0, block_z0, block_w, block_h)
                reproject(
                    source=rasterio.band(src, band),
                    destination=raw_blocks,
                    src_transform=src.transform,
                    src_crs=src.crs,
                    src_nodata=0,
                    dst_transform=dst_transform,
                    dst_crs="EPSG:27700",
                    dst_nodata=0,
                    resampling=Resampling.average,
                )
                raw_blocks = LCM_TO_VEGETATION[raw_blocks]
                tile[:valid_h, :valid_w] = resample_blocks_to_cells(raw_blocks, cell_blocks)
            write_u8_tile(root_path / f"{tile_x:03d}_{tile_z:03d}.u8.gz", tile)
    return tile_z


def _resolve_raster_path(path: Path) -> str:
    if path.suffix.lower() in {".tif", ".tiff"}:
        return str(path)
    if path.suffix.lower() != ".zip":
        raise ValueError("landcover must be a .zip containing a .tif, or a direct .tif/.tiff path")
    with zipfile.ZipFile(path) as archive:
        tifs = [name for name in archive.namelist() if name.lower().endswith((".tif", ".tiff"))]
    if not tifs:
        raise ValueError(f"No GeoTIFF found in {path}")
    preferred = [name for name in tifs if "lcm" in name.lower() or "land" in name.lower()]
    selected = preferred[0] if preferred else tifs[0]
    return f"/vsizip/{path.resolve()}/{selected}"


def _window_transform(geo: dict, width: int, depth: int, x0: int, z0: int, valid_w: int, valid_h: int):
    min_e = float(geo["bng_min_easting"])
    min_n = float(geo["bng_min_northing"])
    max_e = float(geo["bng_max_easting"])
    max_n = float(geo["bng_max_northing"])
    west = min_e + (x0 / width) * (max_e - min_e)
    east = min_e + ((x0 + valid_w) / width) * (max_e - min_e)
    north = max_n - (z0 / depth) * (max_n - min_n)
    south = max_n - ((z0 + valid_h) / depth) * (max_n - min_n)
    return from_bounds(west, south, east, north, valid_w, valid_h)


def _write_debug_geotiff(
    path: Path,
    tile_root: Path,
    manifest: dict,
    tiles_x: int,
    tiles_z: int,
    tile_size: int,
    width_cells: int,
    depth_cells: int,
) -> None:
    from .tiles import read_u8_tile

    arr = np.zeros((depth_cells, width_cells), dtype=np.uint8)
    for tile_z in range(tiles_z):
        for tile_x in range(tiles_x):
            tile = read_u8_tile(tile_root / f"{tile_x:03d}_{tile_z:03d}.u8.gz", tile_size)
            y0 = tile_z * tile_size
            x0 = tile_x * tile_size
            y1 = min(depth_cells, y0 + tile_size)
            x1 = min(width_cells, x0 + tile_size)
            if y0 < depth_cells and x0 < width_cells:
                arr[y0:y1, x0:x1] = tile[: y1 - y0, : x1 - x0]
    transform = from_bounds(
        manifest["georeferencing"]["bng_min_easting"],
        manifest["georeferencing"]["bng_min_northing"],
        manifest["georeferencing"]["bng_max_easting"],
        manifest["georeferencing"]["bng_max_northing"],
        width_cells,
        depth_cells,
    )
    path.parent.mkdir(parents=True, exist_ok=True)
    with rasterio.open(path, "w", driver="GTiff", height=depth, width=width, count=1, dtype="uint8", crs="EPSG:27700", transform=transform) as dst:
        dst.write(arr, 1)
