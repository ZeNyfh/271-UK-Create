from __future__ import annotations

from pathlib import Path
from concurrent.futures import ProcessPoolExecutor
from collections import deque
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
from .tiles import read_u8_tile, write_u8_tile

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

BIOME_REGION_CLASSES: dict[int, dict[str, str]] = {
    class_id: dict(meta) for class_id, meta in VEGETATION_CLASSES.items()
}
BIOME_REGION_CLASSES[0] = {"name": "ocean", "color": "#1f4f7a"}
BIOME_REGION_CLASSES[10] = {"name": "freshwater", "color": "#2b8fc6"}

BIOME_REGION_DEFAULT_FACTOR = 8
BIOME_REGION_HARD_CLASSES = {0, 10}
BIOME_REGION_CLASS_GROUPS = {
    1: "woodland",
    2: "woodland",
    3: "farmed",
    4: "grassland",
    5: "grassland",
    6: "grassland",
    7: "upland",
    8: "wetland",
    9: "upland",
    11: "farmed",
    12: "upland",
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
    vegetation_smoothing: str = "none",
    generate_biome_regions: bool = True,
    biome_region_factor: int = BIOME_REGION_DEFAULT_FACTOR,
    biome_region_smoothing_passes: int = 2,
    biome_region_min_area_cells: int = 3,
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

    smoothing = vegetation_smoothing.lower()
    if smoothing not in {"none", "light", "medium"}:
        raise ValueError("vegetation_smoothing must be none, light, or medium")
    if smoothing != "none":
        console.print(f"Cleaning vegetation speckles with {smoothing} non-freshwater smoothing.")
        vegetation_grid = _read_vegetation_grid(root, tiles_x, tiles_z, tile_size, width_cells, depth_cells)
        vegetation_grid = clean_vegetation_grid(vegetation_grid, smoothing=smoothing)
        _write_vegetation_grid(root, vegetation_grid, tiles_x, tiles_z, tile_size)
    else:
        vegetation_grid = None

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
        "smoothing": {
            "mode": smoothing,
            "freshwater_preserved": True,
            "freshwater_class": 10,
        },
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
    if generate_biome_regions:
        if vegetation_grid is None:
            vegetation_grid = _read_vegetation_grid(root, tiles_x, tiles_z, tile_size, width_cells, depth_cells)
        region_factor = max(1, int(biome_region_factor))
        region_cell_blocks = cell_blocks * region_factor
        region_width_cells = math.ceil(width / region_cell_blocks)
        region_depth_cells = math.ceil(depth / region_cell_blocks)
        region_padded_width_cells = math.ceil(int(world["padded_width"]) / region_cell_blocks)
        region_padded_depth_cells = math.ceil(int(world["padded_depth"]) / region_cell_blocks)
        region_tiles_x = math.ceil(region_padded_width_cells / tile_size)
        region_tiles_z = math.ceil(region_padded_depth_cells / tile_size)
        console.print(
            "Generating biome region layer: "
            f"{region_width_cells}x{region_depth_cells} cells, {region_cell_blocks} blocks/cell."
        )
        biome_regions = generate_biome_region_grid(
            vegetation_grid,
            region_factor=region_factor,
            smoothing_passes=biome_region_smoothing_passes,
            min_area_cells=biome_region_min_area_cells,
        )
        region_root = out / "biome_regions"
        region_root.mkdir(parents=True, exist_ok=True)
        _write_vegetation_grid(region_root, biome_regions, region_tiles_x, region_tiles_z, tile_size, desc="write biome region tiles")
        manifest["biome_regions"] = {
            "path": "biome_regions",
            "extension": ".u8.gz",
            "dtype": "uint8",
            "cell_blocks": region_cell_blocks,
            "source_cell_blocks": cell_blocks,
            "region_factor": region_factor,
            "width_cells": region_width_cells,
            "depth_cells": region_depth_cells,
            "source": "vegetation",
            "classes": {str(class_id): meta for class_id, meta in BIOME_REGION_CLASSES.items()},
            "generation": {
                "method": "coarsened_group_majority_component_cleanup",
                "smoothing_passes": max(0, int(biome_region_smoothing_passes)),
                "min_area_cells": max(1, int(biome_region_min_area_cells)),
                "hard_classes": sorted(BIOME_REGION_HARD_CLASSES),
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


def clean_vegetation_grid(grid: np.ndarray, *, smoothing: str = "light", freshwater_class: int = 10) -> np.ndarray:
    """Remove obvious non-freshwater vegetation speckles while preserving freshwater exactly."""
    mode = smoothing.lower()
    if mode == "none":
        return grid.astype(np.uint8, copy=True)
    if mode not in {"light", "medium"}:
        raise ValueError("smoothing must be none, light, or medium")
    passes = 1 if mode == "light" else 2
    result = grid.astype(np.uint8, copy=True)
    for _ in range(passes):
        result = _majority_smooth_nonfreshwater(result, freshwater_class=freshwater_class)
    result[grid == freshwater_class] = np.uint8(freshwater_class)
    return result


def generate_biome_region_grid(
    grid: np.ndarray,
    *,
    region_factor: int = BIOME_REGION_DEFAULT_FACTOR,
    smoothing_passes: int = 2,
    min_area_cells: int = 3,
) -> np.ndarray:
    """Derive a coarse, Minecraft-biome-oriented vegetation region raster.

    The raw vegetation grid remains the source for surface/flora detail. This
    derived layer intentionally generalises it so biome IDs represent broad
    dominant landcover regions instead of raw raster speckles.
    """
    if grid.size == 0:
        return grid.astype(np.uint8, copy=True)
    factor = max(1, int(region_factor))
    regions = _coarsen_biome_regions(grid.astype(np.uint8, copy=False), factor)
    regions = _remove_small_region_components(regions, min_area_cells=max(1, int(min_area_cells)))
    for _ in range(max(0, int(smoothing_passes))):
        regions = _smooth_biome_region_boundaries(regions)
    return regions.astype(np.uint8, copy=False)


def _coarsen_biome_regions(grid: np.ndarray, factor: int) -> np.ndarray:
    if factor <= 1:
        return grid.copy()
    height, width = grid.shape
    out_h = math.ceil(height / factor)
    out_w = math.ceil(width / factor)
    regions = np.zeros((out_h, out_w), dtype=np.uint8)
    for cell_z in range(out_h):
        z0 = cell_z * factor
        z1 = min(height, z0 + factor)
        for cell_x in range(out_w):
            x0 = cell_x * factor
            x1 = min(width, x0 + factor)
            regions[cell_z, cell_x] = _dominant_biome_region_class(grid[z0:z1, x0:x1])
    return regions


def _dominant_biome_region_class(patch: np.ndarray) -> np.uint8:
    counts = np.bincount(patch.ravel(), minlength=256)
    total = int(patch.size)
    if total <= 0:
        return np.uint8(0)

    freshwater = int(counts[10])
    ocean = int(counts[0])
    # Preserve meaningful water bodies, but do not let a single water cell turn a
    # whole coarse land region into water.
    if freshwater / total >= 0.12 and freshwater >= ocean:
        return np.uint8(10)
    if ocean / total >= 0.35:
        return np.uint8(0)

    group_counts: dict[str, int] = {}
    for class_id, group in BIOME_REGION_CLASS_GROUPS.items():
        count = int(counts[class_id])
        if count:
            group_counts[group] = group_counts.get(group, 0) + count
    if not group_counts:
        return np.uint8(0 if ocean else int(counts.argmax()))

    dominant_group = max(group_counts.items(), key=lambda item: item[1])[0]
    best_class = 0
    best_count = -1
    for class_id, group in BIOME_REGION_CLASS_GROUPS.items():
        if group != dominant_group:
            continue
        count = int(counts[class_id])
        if count > best_count:
            best_class = class_id
            best_count = count
    return np.uint8(best_class)


def _remove_small_region_components(grid: np.ndarray, *, min_area_cells: int) -> np.ndarray:
    height, width = grid.shape
    if height == 0 or width == 0:
        return grid.copy()
    result = grid.copy()
    visited = np.zeros((height, width), dtype=bool)
    min_by_class = {
        8: max(1, min_area_cells - 1),
        11: 1,
        12: max(1, min_area_cells - 1),
    }

    for z in range(height):
        for x in range(width):
            if visited[z, x]:
                continue
            class_id = int(result[z, x])
            component, neighbours = _collect_component(result, visited, x, z, class_id)
            if class_id in BIOME_REGION_HARD_CLASSES:
                continue
            threshold = min_by_class.get(class_id, min_area_cells)
            if len(component) >= threshold:
                continue
            replacement = _dominant_neighbour_class(neighbours, fallback=class_id)
            if replacement == class_id:
                continue
            for cy, cx in component:
                result[cy, cx] = np.uint8(replacement)
    return result


def _collect_component(
    grid: np.ndarray,
    visited: np.ndarray,
    start_x: int,
    start_z: int,
    class_id: int,
) -> tuple[list[tuple[int, int]], list[int]]:
    height, width = grid.shape
    queue: deque[tuple[int, int]] = deque([(start_z, start_x)])
    visited[start_z, start_x] = True
    component: list[tuple[int, int]] = []
    neighbours: list[int] = []
    while queue:
        z, x = queue.popleft()
        component.append((z, x))
        for dz, dx in ((-1, 0), (1, 0), (0, -1), (0, 1)):
            nz = z + dz
            nx = x + dx
            if nz < 0 or nx < 0 or nz >= height or nx >= width:
                continue
            other = int(grid[nz, nx])
            if other == class_id:
                if not visited[nz, nx]:
                    visited[nz, nx] = True
                    queue.append((nz, nx))
            else:
                neighbours.append(other)
    return component, neighbours


def _dominant_neighbour_class(neighbours: list[int], *, fallback: int) -> int:
    if not neighbours:
        return fallback
    counts = np.bincount(np.asarray(neighbours, dtype=np.uint8), minlength=256)
    for hard_class in BIOME_REGION_HARD_CLASSES:
        counts[hard_class] = 0
    if int(counts.max()) <= 0:
        return fallback
    return int(counts.argmax())


def _smooth_biome_region_boundaries(grid: np.ndarray) -> np.ndarray:
    height, width = grid.shape
    output = grid.copy()
    if height == 0 or width == 0:
        return output
    padded = np.pad(grid, ((1, 1), (1, 1)), mode="edge")
    for z in range(height):
        for x in range(width):
            current = int(grid[z, x])
            if current in BIOME_REGION_HARD_CLASSES:
                continue
            window = padded[z : z + 3, x : x + 3].ravel()
            counts = np.bincount(window, minlength=256)
            for hard_class in BIOME_REGION_HARD_CLASSES:
                counts[hard_class] = 0
            best = int(counts.argmax())
            if best != current and int(counts[best]) >= 6:
                output[z, x] = np.uint8(best)
    return output


def _majority_smooth_nonfreshwater(grid: np.ndarray, *, freshwater_class: int) -> np.ndarray:
    height, width = grid.shape
    output = grid.copy()
    if height == 0 or width == 0:
        return output
    stripe_rows = 2048
    for z0 in range(0, height, stripe_rows):
        z1 = min(height, z0 + stripe_rows)
        source_z0 = max(0, z0 - 1)
        source_z1 = min(height, z1 + 1)
        stripe = grid[source_z0:source_z1]
        padded = np.pad(stripe, ((1, 1), (1, 1)), mode="edge")
        local_z0 = z0 - source_z0 + 1
        local_z1 = local_z0 + (z1 - z0)
        current = padded[local_z0:local_z1, 1 : width + 1]
        best_class = current.copy()
        best_count = np.zeros_like(current, dtype=np.uint8)
        nonfresh_neighbour_count = np.zeros_like(current, dtype=np.uint8)
        for class_id in VEGETATION_CLASSES:
            if class_id == freshwater_class:
                continue
            count = np.zeros_like(current, dtype=np.uint8)
            for dz in (-1, 0, 1):
                for dx in (-1, 0, 1):
                    if dx == 0 and dz == 0:
                        continue
                    neighbour = padded[local_z0 + dz : local_z1 + dz, 1 + dx : width + 1 + dx]
                    count += neighbour == class_id
            nonfresh_neighbour_count += count
            replace_best = count > best_count
            best_count[replace_best] = count[replace_best]
            best_class[replace_best] = np.uint8(class_id)
        mutable = current != freshwater_class
        strong_majority = (best_count >= 5) & (best_count * 10 >= nonfresh_neighbour_count * 7)
        replace = mutable & strong_majority & (best_class != current)
        output[z0:z1][replace] = best_class[replace]
    return output


def _read_vegetation_grid(tile_root: Path, tiles_x: int, tiles_z: int, tile_size: int, width_cells: int, depth_cells: int) -> np.ndarray:
    grid = np.zeros((depth_cells, width_cells), dtype=np.uint8)
    for tile_z in tqdm(range(tiles_z), desc="read vegetation tiles"):
        for tile_x in range(tiles_x):
            tile = read_u8_tile(tile_root / f"{tile_x:03d}_{tile_z:03d}.u8.gz", tile_size)
            y0 = tile_z * tile_size
            x0 = tile_x * tile_size
            y1 = min(depth_cells, y0 + tile_size)
            x1 = min(width_cells, x0 + tile_size)
            if y0 < depth_cells and x0 < width_cells:
                grid[y0:y1, x0:x1] = tile[: y1 - y0, : x1 - x0]
    return grid


def _write_vegetation_grid(tile_root: Path, grid: np.ndarray, tiles_x: int, tiles_z: int, tile_size: int, *, desc: str = "write cleaned vegetation tiles") -> None:
    height, width = grid.shape
    for tile_z in tqdm(range(tiles_z), desc=desc):
        for tile_x in range(tiles_x):
            tile = np.zeros((tile_size, tile_size), dtype=np.uint8)
            y0 = tile_z * tile_size
            x0 = tile_x * tile_size
            y1 = min(height, y0 + tile_size)
            x1 = min(width, x0 + tile_size)
            if y0 < height and x0 < width:
                tile[: y1 - y0, : x1 - x0] = grid[y0:y1, x0:x1]
            write_u8_tile(tile_root / f"{tile_x:03d}_{tile_z:03d}.u8.gz", tile, tile_size)


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
