from __future__ import annotations

import argparse
import json
import math
from pathlib import Path

import numpy as np

from ukgeo.tiles import read_layer_tile, region_metadata, write_region_file_from_tiles
from ukgeo.vegetation import clean_vegetation_grid, generate_biome_region_grid


def _read_grid(root: Path, layer: dict, tile_size: int, width_cells: int, depth_cells: int, padded_width_cells: int, padded_depth_cells: int) -> np.ndarray:
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


def _write_region_layer(dst_root: Path, layer_path: str, grid: np.ndarray, *, tile_size: int, region_tiles: int, padded_width_cells: int, padded_depth_cells: int) -> None:
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


def refine(src: Path, dst: Path, *, vegetation_smoothing: str, biome_region_factor: int, biome_region_smoothing_passes: int, biome_region_min_area_cells: int) -> None:
    manifest = json.loads((src / "manifest.json").read_text())
    tile_size = int(manifest["tile_size"])
    region_tiles = int(manifest["vegetation"].get("region_tiles", 8))
    world = manifest["world"]
    vegetation = manifest["vegetation"]
    cell_blocks = int(vegetation["cell_blocks"])
    width_cells = int(vegetation["width_cells"])
    depth_cells = int(vegetation["depth_cells"])
    padded_width_cells = math.ceil(int(world["padded_width"]) / cell_blocks)
    padded_depth_cells = math.ceil(int(world["padded_depth"]) / cell_blocks)

    vegetation_grid = _read_grid(src, vegetation, tile_size, width_cells, depth_cells, padded_width_cells, padded_depth_cells)
    cleaned = clean_vegetation_grid(vegetation_grid, smoothing=vegetation_smoothing)

    dst.mkdir(parents=True, exist_ok=True)
    (dst / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    _write_region_layer(dst, "vegetation", cleaned, tile_size=tile_size, region_tiles=region_tiles, padded_width_cells=padded_width_cells, padded_depth_cells=padded_depth_cells)

    vegetation["smoothing"] = {
        "mode": vegetation_smoothing,
        "freshwater_preserved": False,
        "freshwater_class": 10,
        "freshwater_cleanup": True,
    }
    vegetation.update(region_metadata("vegetation", "uint8", region_tiles=region_tiles))

    region_factor = max(1, int(biome_region_factor))
    region_cell_blocks = cell_blocks * region_factor
    region_width_cells = math.ceil(int(world["width"]) / region_cell_blocks)
    region_depth_cells = math.ceil(int(world["depth"]) / region_cell_blocks)
    region_padded_width_cells = math.ceil(int(world["padded_width"]) / region_cell_blocks)
    region_padded_depth_cells = math.ceil(int(world["padded_depth"]) / region_cell_blocks)
    biome_regions = generate_biome_region_grid(
        cleaned,
        region_factor=region_factor,
        smoothing_passes=biome_region_smoothing_passes,
        min_area_cells=biome_region_min_area_cells,
    )
    _write_region_layer(dst, "biome_regions", biome_regions, tile_size=tile_size, region_tiles=region_tiles, padded_width_cells=region_padded_width_cells, padded_depth_cells=region_padded_depth_cells)

    manifest["biome_regions"] = {
        "path": "biome_regions",
        "extension": ".u8",
        "dtype": "uint8",
        "cell_blocks": region_cell_blocks,
        "source_cell_blocks": cell_blocks,
        "region_factor": region_factor,
        "width_cells": region_width_cells,
        "depth_cells": region_depth_cells,
        "source": "vegetation",
        "classes": manifest["biome_regions"]["classes"],
        "generation": {
            "method": "coarsened_group_majority_component_cleanup",
            "smoothing_passes": max(0, int(biome_region_smoothing_passes)),
            "min_area_cells": max(1, int(biome_region_min_area_cells)),
            "hard_classes": [0, 10],
        },
        **region_metadata("biome_regions", "uint8", region_tiles=region_tiles),
    }
    (dst / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(
        "refined vegetation: freshwater_cells={} biome_region_factor={} biome_region_smoothing_passes={}".format(
            int(np.count_nonzero(cleaned == 10)),
            region_factor,
            biome_region_smoothing_passes,
        )
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--src", required=True, type=Path)
    parser.add_argument("--dst", required=True, type=Path)
    parser.add_argument("--vegetation-smoothing", default="medium")
    parser.add_argument("--biome-region-factor", type=int, default=6)
    parser.add_argument("--biome-region-smoothing-passes", type=int, default=4)
    parser.add_argument("--biome-region-min-area-cells", type=int, default=6)
    args = parser.parse_args()
    refine(
        args.src,
        args.dst,
        vegetation_smoothing=args.vegetation_smoothing,
        biome_region_factor=args.biome_region_factor,
        biome_region_smoothing_passes=args.biome_region_smoothing_passes,
        biome_region_min_area_cells=args.biome_region_min_area_cells,
    )


if __name__ == "__main__":
    main()
