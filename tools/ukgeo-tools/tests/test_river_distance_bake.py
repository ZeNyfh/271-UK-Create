from __future__ import annotations

import json
from pathlib import Path

import numpy as np

from ukgeo.rivers import bake_river_distance_grids
from ukgeo.tiles import read_u8_tile, u8_extension, write_u8_tile


def test_bake_river_distance_grids_tiny_fixture(tmp_path: Path):
    tile_size = 16
    rivers = np.zeros((tile_size, tile_size), dtype=np.uint8)
    half = np.zeros((tile_size, tile_size), dtype=np.uint8)
    rivers[8, 8] = 255
    half[8, 8] = 3

    river_dir = tmp_path / "water" / "rivers"
    half_dir = tmp_path / "water" / "river_half_width"
    write_u8_tile(river_dir / f"000_000{u8_extension()}", rivers, tile_size)
    write_u8_tile(half_dir / f"000_000{u8_extension()}", half, tile_size)

    manifest = {
        "format": "uk-raster-tiles-v1",
        "tile_size": tile_size,
        "world": {
            "width": tile_size,
            "depth": tile_size,
            "padded_width": tile_size,
            "padded_depth": tile_size,
            "minecraft_min_x": 0,
            "minecraft_min_z": 0,
            "minecraft_max_x": tile_size - 1,
            "minecraft_max_z": tile_size - 1,
        },
        "axis_scale": {"x": 1.0, "z": 1.0},
        "georeferencing": {
            "crs": "EPSG:27700",
            "bng_min_easting": 0,
            "bng_min_northing": 0,
            "bng_max_easting": tile_size,
            "bng_max_northing": tile_size,
        },
        "height": {"path": "height", "sea_level_y": 63, "nodata": -32768, "extension": ".r16", "dtype": "int16_le"},
        "rivers": {
            "path": "water/rivers",
            "extension": u8_extension(),
            "dtype": "uint8",
            "half_width_path": "water/river_half_width",
            "max_half_width": 3,
        },
    }
    (tmp_path / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")

    bake_river_distance_grids(tmp_path)

    updated = json.loads((tmp_path / "manifest.json").read_text())
    rivers_meta = updated["rivers"]
    assert rivers_meta["distance_path"] == "water/river_distance"
    assert rivers_meta["nearest_half_width_path"] == "water/river_nearest_half_width"
    assert rivers_meta["distance_units"] == "half_blocks"
    assert rivers_meta["distance_search_radius"] == max(3, 1) + 4

    dist = read_u8_tile(tmp_path / "water" / "river_distance" / f"000_000{u8_extension()}", tile_size)
    nearest_hw = read_u8_tile(tmp_path / "water" / "river_nearest_half_width" / f"000_000{u8_extension()}", tile_size)

    assert dist[8, 8] == 0
    assert nearest_hw[8, 8] == 3
    # One block east: euclidean 1 → code 2
    assert dist[8, 9] == 2
    assert nearest_hw[8, 9] == 3
    # Diagonal √2 ≈ 1.414 → round(2.828) = 3
    assert dist[9, 9] == 3
    # Far cell beyond search radius stays 255 (and may be sparse-filled on read as 0 from missing;
    # tile is written so check a within-tile far cell).
    far = dist[0, 0]
    assert far == 255 or far > dist[8, 9]
