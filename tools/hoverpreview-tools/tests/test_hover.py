from hoverpreview_tools.hover import HoverSample, _default_preview_ore_enabled, _initial_window_geometry, _measurement_text


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
