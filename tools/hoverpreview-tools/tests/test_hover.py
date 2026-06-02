from hoverpreview_tools.hover_previews import _minecraft_origin

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
