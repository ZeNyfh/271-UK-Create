from __future__ import annotations

import argparse
import json
import math
import shutil
import time
from pathlib import Path

import numpy as np
from scipy import ndimage

from ukgeo.tiles import read_layer_tile, region_metadata, write_region_file_from_tiles


FRESHWATER = 10
OCEAN = 0


def _load_full_grid(root: Path, layer: dict, *, tile_size: int, padded_width_cells: int, padded_depth_cells: int, width_cells: int, depth_cells: int) -> np.ndarray:
    tiles_x = math.ceil(padded_width_cells / tile_size)
    tiles_z = math.ceil(padded_depth_cells / tile_size)
    grid = np.zeros((depth_cells, width_cells), dtype=np.uint8)
    for tile_z in range(tiles_z):
        for tile_x in range(tiles_x):
            tile = read_layer_tile(root, layer, tile_x, tile_z, tile_size)
            y0 = tile_z * tile_size
            x0 = tile_x * tile_size
            y1 = min(depth_cells, y0 + tile_size)
            x1 = min(width_cells, x0 + tile_size)
            if y0 < depth_cells and x0 < width_cells:
                grid[y0:y1, x0:x1] = tile[: y1 - y0, : x1 - x0]
    return grid


def _write_full_grid_regions(dst_root: Path, layer_path: str, grid: np.ndarray, *, tile_size: int, region_tiles: int, padded_width_cells: int, padded_depth_cells: int) -> None:
    tiles_x = math.ceil(padded_width_cells / tile_size)
    tiles_z = math.ceil(padded_depth_cells / tile_size)
    regions_x = math.ceil(tiles_x / region_tiles)
    regions_z = math.ceil(tiles_z / region_tiles)
    height, width = grid.shape
    region_root = dst_root / layer_path / "regions"
    region_root.mkdir(parents=True, exist_ok=True)
    for region_z in range(regions_z):
        for region_x in range(regions_x):
            tiles: list[np.ndarray | None] = []
            for local_z in range(region_tiles):
                for local_x in range(region_tiles):
                    tile_x = region_x * region_tiles + local_x
                    tile_z = region_z * region_tiles + local_z
                    x0 = tile_x * tile_size
                    z0 = tile_z * tile_size
                    if x0 >= padded_width_cells or z0 >= padded_depth_cells:
                        tiles.append(None)
                        continue
                    tile = np.zeros((tile_size, tile_size), dtype=np.uint8)
                    x1 = min(width, x0 + tile_size)
                    z1 = min(height, z0 + tile_size)
                    if x0 < width and z0 < height:
                        tile[: z1 - z0, : x1 - x0] = grid[z0:z1, x0:x1]
                    tiles.append(tile if np.any(tile) else None)
            write_region_file_from_tiles(
                region_root / f"{region_x:03d}_{region_z:03d}.u8rg",
                tiles=tiles,
                tile_size=tile_size,
                region_tiles=region_tiles,
                dtype="uint8",
            )


def _majority_nonfreshwater(grid: np.ndarray, mask: np.ndarray) -> np.ndarray:
    out = grid.copy()
    coords = np.argwhere(mask)
    for z, x in coords:
        z0 = max(0, z - 1)
        z1 = min(grid.shape[0], z + 2)
        x0 = max(0, x - 1)
        x1 = min(grid.shape[1], x + 2)
        window = grid[z0:z1, x0:x1].ravel()
        window = window[window != FRESHWATER]
        if window.size == 0:
            out[z, x] = OCEAN
            continue
        counts = np.bincount(window, minlength=256)
        out[z, x] = np.uint8(int(counts.argmax()))
    return out


def _smooth_freshwater(grid: np.ndarray) -> np.ndarray:
    freshwater = grid == FRESHWATER
    cross = ndimage.generate_binary_structure(2, 1)
    full = np.ones((3, 3), dtype=bool)
    freshwater = ndimage.binary_closing(freshwater, structure=full, iterations=2)
    freshwater = ndimage.binary_opening(freshwater, structure=cross, iterations=1)
    freshwater = ndimage.binary_closing(freshwater, structure=cross, iterations=2)
    orth_kernel = np.array([[0, 1, 0], [1, 0, 1], [0, 1, 0]], dtype=np.uint8)
    for _ in range(3):
        counts = ndimage.convolve(freshwater.astype(np.uint8), np.ones((3, 3), dtype=np.uint8), mode="nearest")
        orth_counts = ndimage.convolve(freshwater.astype(np.uint8), orth_kernel, mode="nearest")
        freshwater = (freshwater & ((counts >= 5) | (orth_counts >= 2))) | (~freshwater & ((counts >= 7) | (orth_counts >= 3)))
    labels, count = ndimage.label(freshwater, structure=cross)
    if count > 0:
        sizes = np.bincount(labels.ravel())
        small = sizes < 18
        small[0] = False
        removed_mask = small[labels]
    else:
        removed_mask = np.zeros_like(freshwater, dtype=bool)
    freshwater = freshwater & ~removed_mask
    cleaned = grid.copy()
    cleaned[freshwater] = FRESHWATER
    cleared = (grid == FRESHWATER) & ~freshwater
    if np.any(cleared):
        cleaned = _majority_nonfreshwater(cleaned, cleared)
    return cleaned


def _smooth_biome_regions(grid: np.ndarray) -> np.ndarray:
    result = grid.copy()
    hard = (grid == OCEAN) | (grid == FRESHWATER)
    for _ in range(3):
        source = result.copy()
        for class_id in np.unique(source):
            if class_id in (OCEAN, FRESHWATER):
                continue
            counts = ndimage.convolve((source == class_id).astype(np.uint8), np.ones((5, 5), dtype=np.uint8), mode="nearest")
            current = ndimage.convolve((source == source).astype(np.uint8), np.ones((1, 1), dtype=np.uint8), mode="nearest")
            replace = (~hard) & (counts >= 14) & (source != class_id)
            result[replace] = np.uint8(class_id)
        labels, count = ndimage.label(~hard, structure=ndimage.generate_binary_structure(2, 1))
        _ = current  # keep the per-pass convolution code explicit without linter complaints
    return result


def postprocess(root: Path) -> None:
    manifest = json.loads((root / "manifest.json").read_text())
    tile_size = int(manifest["tile_size"])
    region_tiles = int(manifest["vegetation"].get("region_tiles", 8))
    world = manifest["world"]

    vegetation = manifest["vegetation"]
    vegetation_cell_blocks = int(vegetation["cell_blocks"])
    veg_width_cells = int(vegetation["width_cells"])
    veg_depth_cells = int(vegetation["depth_cells"])
    veg_padded_width_cells = math.ceil(int(world["padded_width"]) / vegetation_cell_blocks)
    veg_padded_depth_cells = math.ceil(int(world["padded_depth"]) / vegetation_cell_blocks)
    vegetation_grid = _load_full_grid(
        root,
        vegetation,
        tile_size=tile_size,
        padded_width_cells=veg_padded_width_cells,
        padded_depth_cells=veg_padded_depth_cells,
        width_cells=veg_width_cells,
        depth_cells=veg_depth_cells,
    )
    cleaned_vegetation = _smooth_freshwater(vegetation_grid)
    _write_full_grid_regions(
        root,
        "vegetation",
        cleaned_vegetation,
        tile_size=tile_size,
        region_tiles=region_tiles,
        padded_width_cells=veg_padded_width_cells,
        padded_depth_cells=veg_padded_depth_cells,
    )
    vegetation["smoothing"] = {
        "mode": "postprocessed-medium",
        "freshwater_preserved": False,
        "freshwater_class": 10,
        "freshwater_cleanup": True,
    }
    vegetation.update(region_metadata("vegetation", "uint8", region_tiles=region_tiles))

    biome = manifest["biome_regions"]
    biome_cell_blocks = int(biome["cell_blocks"])
    biome_width_cells = int(biome["width_cells"])
    biome_depth_cells = int(biome["depth_cells"])
    biome_padded_width_cells = math.ceil(int(world["padded_width"]) / biome_cell_blocks)
    biome_padded_depth_cells = math.ceil(int(world["padded_depth"]) / biome_cell_blocks)
    biome_grid = _load_full_grid(
        root,
        biome,
        tile_size=tile_size,
        padded_width_cells=biome_padded_width_cells,
        padded_depth_cells=biome_padded_depth_cells,
        width_cells=biome_width_cells,
        depth_cells=biome_depth_cells,
    )
    smoothed_biome = _smooth_biome_regions(biome_grid)
    _write_full_grid_regions(
        root,
        "biome_regions",
        smoothed_biome,
        tile_size=tile_size,
        region_tiles=region_tiles,
        padded_width_cells=biome_padded_width_cells,
        padded_depth_cells=biome_padded_depth_cells,
    )
    biome["generation"]["method"] = "postprocessed-majority-smoothing"
    biome["generation"]["smoothing_passes"] = 3
    biome["generation"]["min_area_cells"] = max(6, int(biome["generation"].get("min_area_cells", 3)))
    biome.update(region_metadata("biome_regions", "uint8", region_tiles=region_tiles))

    manifest_path = root / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
    print(
        "postprocessed vegetation/biomes freshwater_cells={} biome_nonzero={}".format(
            int(np.count_nonzero(cleaned_vegetation == FRESHWATER)),
            int(np.count_nonzero(smoothed_biome)),
        )
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("root", type=Path)
    args = parser.parse_args()
    postprocess(args.root)


if __name__ == "__main__":
    main()
