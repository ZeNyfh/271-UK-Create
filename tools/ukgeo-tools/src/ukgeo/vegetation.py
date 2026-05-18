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


def make_vegetation_tiles(*, landcover: Path, manifest_path: Path, out: Path, band: int = 1, debug_geotiff: Path | None = None, jobs: int = 4) -> None:
    manifest = read_manifest(manifest_path)
    geo = manifest["georeferencing"]
    world = manifest["world"]
    tile_size = int(manifest["tile_size"])
    width = int(world["width"])
    depth = int(world["depth"])
    padded_width = int(world["padded_width"])
    padded_depth = int(world["padded_depth"])
    tiles_x = math.ceil(padded_width / tile_size)
    tiles_z = math.ceil(padded_depth / tile_size)
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

    tasks = [
        (
            raster_path,
            band,
            geo,
            width,
            depth,
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
        _write_debug_geotiff(debug_geotiff, out / "vegetation", manifest, tiles_x, tiles_z, tile_size)


def _write_vegetation_tile_row(task: tuple) -> int:
    raster_path, band, geo, width, depth, tile_size, tiles_x, root, tile_z = task
    root_path = Path(root)
    with rasterio.open(raster_path) as src:
        for tile_x in range(tiles_x):
            tile = np.zeros((tile_size, tile_size), dtype=np.uint8)
            x0 = tile_x * tile_size
            z0 = tile_z * tile_size
            valid_w = max(0, min(tile_size, width - x0))
            valid_h = max(0, min(tile_size, depth - z0))
            if valid_w > 0 and valid_h > 0:
                raw = np.zeros((valid_h, valid_w), dtype=np.uint8)
                dst_transform = _window_transform(geo, width, depth, x0, z0, valid_w, valid_h)
                reproject(
                    source=rasterio.band(src, band),
                    destination=raw,
                    src_transform=src.transform,
                    src_crs=src.crs,
                    src_nodata=0,
                    dst_transform=dst_transform,
                    dst_crs="EPSG:27700",
                    dst_nodata=0,
                    resampling=Resampling.nearest,
                )
                tile[:valid_h, :valid_w] = LCM_TO_VEGETATION[raw]
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


def _write_debug_geotiff(path: Path, tile_root: Path, manifest: dict, tiles_x: int, tiles_z: int, tile_size: int) -> None:
    from .tiles import read_u8_tile

    world = manifest["world"]
    width = int(world["width"])
    depth = int(world["depth"])
    arr = np.zeros((depth, width), dtype=np.uint8)
    for tile_z in range(tiles_z):
        for tile_x in range(tiles_x):
            tile = read_u8_tile(tile_root / f"{tile_x:03d}_{tile_z:03d}.u8.gz", tile_size)
            y0 = tile_z * tile_size
            x0 = tile_x * tile_size
            y1 = min(depth, y0 + tile_size)
            x1 = min(width, x0 + tile_size)
            if y0 < depth and x0 < width:
                arr[y0:y1, x0:x1] = tile[: y1 - y0, : x1 - x0]
    transform = from_bounds(
        manifest["georeferencing"]["bng_min_easting"],
        manifest["georeferencing"]["bng_min_northing"],
        manifest["georeferencing"]["bng_max_easting"],
        manifest["georeferencing"]["bng_max_northing"],
        width,
        depth,
    )
    path.parent.mkdir(parents=True, exist_ok=True)
    with rasterio.open(path, "w", driver="GTiff", height=depth, width=width, count=1, dtype="uint8", crs="EPSG:27700", transform=transform) as dst:
        dst.write(arr, 1)
