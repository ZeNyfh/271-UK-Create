from hoverpreview_tools.hover import (
    HoverSample,
    PreviewHeightSampler,
    _default_preview_ore_enabled,
    _initial_window_geometry,
    _measurement_text,
)
from hoverpreview_tools.hover_previews import _minecraft_origin


def test_default_preview_ores_are_core_real_world_metals():
    enabled = {"coal", "iron", "copper", "zinc", "gold"}
    disabled = {"andesite", "diorite", "granite", "ochrum", "calcite", "scoria", "tuff"}

    assert all(_default_preview_ore_enabled(ore) for ore in enabled)
    assert not any(_default_preview_ore_enabled(ore) for ore in disabled)


class _FakeTk:
    def __init__(self, width: int, height: int):
        self._width = width
        self._height = height

    def winfo_screenwidth(self) -> int:
        return self._width

    def winfo_screenheight(self) -> int:
        return self._height


def test_initial_window_geometry_uses_real_viewport_size():
    geometry = _initial_window_geometry(_FakeTk(1440, 1000), image_width=12000, image_height=12000)

    assert geometry.startswith("1408x920+")


def test_measurement_text_uses_positive_coordinate_distance():
    start = HoverSample(100, 250, 0, 0, 0, 0, 0, 0, None, None, None)
    end = HoverSample(80, 280, 0, 0, 0, 0, 0, 0, None, None, None)

    assert _measurement_text(start, end) == "36.1 blocks | dx 20, dz 30"


def test_preview_sampler_reports_nottingham_origin(tmp_path):
    index = {
        "scale": 1,
        "tile_size": 512,
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
        "layers": [],
    }

    sample = PreviewHeightSampler(tmp_path, index).sample(17588, 36925)

    assert sample is not None
    assert (sample.minecraft_x, sample.minecraft_z) == (0, 0)
    assert round(sample.bng_easting or 0) == 457301
    assert round(sample.bng_northing or 0) == 339937


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
