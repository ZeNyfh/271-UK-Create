import math

from ukgeo.preview import u8_layer_grid


def test_vegetation_layer_grid_uses_coarse_tiles():
    manifest = {
        "tile_size": 512,
        "world": {
            "width": 25000,
            "depth": 50000,
            "padded_width": 26112,
            "padded_depth": 50176,
        },
        "vegetation": {
            "cell_blocks": 2,
            "width_cells": 12500,
            "depth_cells": 25000,
        },
    }
    tiles_x, tiles_z, width_cells, depth_cells, cell_blocks = u8_layer_grid(manifest, "vegetation")
    assert cell_blocks == 2
    assert width_cells == 12500
    assert depth_cells == 25000
    assert tiles_x == math.ceil(26112 / (512 * 2))
    assert tiles_z == math.ceil(50176 / (512 * 2))
