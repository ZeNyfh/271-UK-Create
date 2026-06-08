import numpy as np
from PIL import Image

from hoverpreview_tools.hover_previews import (
    PREVIEW_RIVER_MAX_RADIUS,
    PREVIEW_RIVER_MIN_RADIUS,
    _minecraft_origin,
    _river_preview_radii,
    _save_visual_layer,
)

def test_hover_preview_index_origin_metadata_uses_nottingham_zero_zero():
    manifest = {
        "world": {
            "width": 25000,
            "depth": 50000,
            "minecraft_min_x": -17588,
            "minecraft_min_z": -36925,
        },
        "georeferencing": {
            "bng_min_easting": 0,
            "bng_min_northing": 0,
            "bng_max_easting": 650000,
            "bng_max_northing": 1300000,
        },
    }

    origin = _minecraft_origin(manifest)

    assert origin["data_x"] == 17588
    assert origin["data_z"] == 36925
    assert round(origin["bng_easting"]) == 457301
    assert round(origin["bng_northing"]) == 339937


def test_save_visual_layer_writes_tile_metadata(tmp_path):
    image = Image.new("RGB", (300, 260), (12, 34, 56))

    mips = _save_visual_layer(tmp_path, image, "layers/height.png")

    assert mips[0]["tiles"]["size"] == 256
    assert mips[0]["tiles"]["columns"] == 2
    assert mips[0]["tiles"]["rows"] == 2
    assert mips[0]["tiles"]["template"] == "tiles/1/layers/height/{x}_{y}.png"
    assert (tmp_path / "tiles" / "1" / "layers" / "height" / "0_0.png").exists()
    assert (tmp_path / "tiles" / "1" / "layers" / "height" / "1_1.png").exists()


def test_river_preview_radii_use_half_width_hierarchy():
    river = np.array([[255, 255, 255, 255, 0]], dtype=np.uint8)
    half_width = np.array([[2, 10, 32, 0, 32]], dtype=np.uint8)

    radii = _river_preview_radii(river, half_width, "river_half_width")

    assert radii[0, 0] == PREVIEW_RIVER_MIN_RADIUS
    assert radii[0, 1] > radii[0, 0]
    assert radii[0, 2] == PREVIEW_RIVER_MAX_RADIUS
    assert radii[0, 3] == PREVIEW_RIVER_MIN_RADIUS
    assert radii[0, 4] == 0
