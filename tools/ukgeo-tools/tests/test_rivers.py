from __future__ import annotations

import geopandas as gpd
import numpy as np
from shapely.geometry import LineString, MultiLineString

from ukgeo.rivers import (
    _Edge,
    _InputLine,
    _apply_axis_scale_to_half_widths,
    _apply_gb_width_adjustments,
    _coerce_source_order,
    _dataset_half_width,
    _extract_lines,
    _horizontal_axis_scale,
    _irish_preview_radius_for_order,
    _normalize_computed_order_to_source_scale,
    _apply_output_width_scale,
    _preview_radius_for_edge,
    _preview_radius_for_half_width,
    _preview_radius_for_order,
    _strahler_widths,
    _thin_top_order_half_widths,
)


def test_coerce_source_order_rejects_missing_or_non_positive_values():
    assert _coerce_source_order(None) is None
    assert _coerce_source_order(0) is None
    assert _coerce_source_order(-1) is None
    assert _coerce_source_order("abc") is None
    assert _coerce_source_order(3) == 3


def test_extract_lines_preserves_source_order_for_each_part():
    frame = gpd.GeoDataFrame(
        {
            "ORDER_": [4, None],
            "geometry": [
                MultiLineString(
                    [
                        [(0, 0), (1, 1)],
                        [(1, 1), (2, 2)],
                    ]
                ),
                LineString([(2, 2), (3, 3)]),
            ],
        },
        geometry="geometry",
        crs="EPSG:29902",
    )

    lines = _extract_lines(frame)

    assert len(lines) == 3
    assert [item.source_order for item in lines] == [4, 4, None]


def test_extract_lines_preserves_source_dataset():
    frame = gpd.GeoDataFrame(
        {
            "ORDER_": [5],
            "source_dataset": ["ni_river_segment"],
            "geometry": [
                MultiLineString(
                    [
                        [(0, 0), (1, 0)],
                        [(1, 0), (2, 0)],
                    ]
                )
            ],
        },
        geometry="geometry",
        crs="EPSG:27700",
    )

    lines = _extract_lines(frame)

    assert len(lines) == 2
    assert [item.source_order for item in lines] == [5, 5]
    assert [item.source_dataset for item in lines] == ["ni_river_segment", "ni_river_segment"]


def test_extract_lines_preserves_topology_fields_for_mergeable_multiline():
    frame = gpd.GeoDataFrame(
        {
            "ORDER_": [5],
            "source_dataset": ["os_open_rivers_gb"],
            "start_node": ["A"],
            "end_node": ["B"],
            "flow_direction": ["in direction"],
            "geometry": [
                MultiLineString(
                    [
                        [(0, 0), (1, 0)],
                        [(1, 0), (2, 0)],
                    ]
                )
            ],
        },
        geometry="geometry",
        crs="EPSG:27700",
    )

    lines = _extract_lines(frame)

    assert len(lines) == 1
    assert lines[0].source_dataset == "os_open_rivers_gb"
    assert lines[0].source_start_node == "A"
    assert lines[0].source_end_node == "B"
    assert lines[0].source_flow_direction == "in direction"
    assert list(lines[0].line.coords) == [(0.0, 0.0), (1.0, 0.0), (2.0, 0.0)]


def test_extract_lines_treats_nan_topology_fields_as_missing():
    frame = gpd.GeoDataFrame(
        {
            "ORDER_": [np.nan],
            "source_dataset": ["epa_river_network_routes_ie"],
            "start_node": [np.nan],
            "end_node": [np.nan],
            "flow_direction": [np.nan],
            "geometry": [LineString([(10.2, 10.2), (12.8, 11.6)])],
        },
        geometry="geometry",
        crs="EPSG:27700",
    )

    lines = _extract_lines(frame)

    assert len(lines) == 1
    assert lines[0].source_order is None
    assert lines[0].source_start_node is None
    assert lines[0].source_end_node is None
    assert lines[0].source_flow_direction is None


def test_dataset_half_width_only_thins_irish_datasets():
    assert _dataset_half_width(10, "epa_river_network_routes_ie") == 7
    assert _dataset_half_width(10, "ni_river_segment") == 7
    assert _dataset_half_width(10, "os_open_rivers_gb") == 10
    assert _dataset_half_width(1, "ni_river_segment") == 1


def test_thin_top_order_half_widths_scales_top_four_order_levels_for_irish_datasets_only():
    edges = [
        _Edge(LineString([(0, 0), (1, 1)]), 8, 20, "epa_river_network_routes_ie"),
        _Edge(LineString([(0, 0), (1, 1)]), 7, 18, "epa_river_network_routes_ie"),
        _Edge(LineString([(0, 0), (1, 1)]), 6, 16, "epa_river_network_routes_ie"),
        _Edge(LineString([(0, 0), (1, 1)]), 5, 14, "epa_river_network_routes_ie"),
        _Edge(LineString([(0, 0), (1, 1)]), 4, 12, "epa_river_network_routes_ie"),
        _Edge(LineString([(0, 0), (1, 1)]), 8, 20, "os_open_rivers_gb"),
    ]

    _thin_top_order_half_widths(edges)

    assert [edge.half_width for edge in edges[:5]] == [10, 12, 13, 12, 12]
    assert edges[5].half_width == 20


def test_apply_gb_width_adjustments_keeps_order_one_and_boosts_larger_gb_rivers():
    edges = [
        _Edge(LineString([(0, 0), (1, 1)]), 1, 4, "os_open_rivers_gb"),
        _Edge(LineString([(0, 0), (1, 1)]), 2, 8, "os_open_rivers_gb"),
        _Edge(LineString([(0, 0), (1, 1)]), 5, 28, "os_open_rivers_gb"),
        _Edge(LineString([(0, 0), (1, 1)]), 5, 28, "ni_river_segment"),
    ]

    _apply_gb_width_adjustments(edges, 2.0)

    assert [edge.half_width for edge in edges] == [4, 10, 42, 28]


def test_normalize_computed_order_to_source_scale_compresses_to_source_max():
    assert _normalize_computed_order_to_source_scale(8, 8, 7) == 7
    assert _normalize_computed_order_to_source_scale(7, 8, 7) == 6
    assert _normalize_computed_order_to_source_scale(6, 8, 7) == 5
    assert _normalize_computed_order_to_source_scale(5, 8, 7) == 4
    assert _normalize_computed_order_to_source_scale(3, 8, 7) == 3


def test_preview_radius_for_order_preserves_legacy_gb_strahler_steps():
    assert _preview_radius_for_order(1) == 1
    assert _preview_radius_for_order(2) == 1
    assert _preview_radius_for_order(3) == 2
    assert _preview_radius_for_order(4) == 3
    assert _preview_radius_for_order(5) == 5
    assert _preview_radius_for_order(6) == 6


def test_irish_preview_radius_suppresses_order_one_minor_streams():
    assert _irish_preview_radius_for_order(1) == 0
    assert _irish_preview_radius_for_order(2) == 0
    assert _irish_preview_radius_for_order(3) == 1
    assert _irish_preview_radius_for_order(4) == 2
    assert _irish_preview_radius_for_order(6) == 4
    assert _irish_preview_radius_for_order(7) == 5


def test_preview_radius_for_edge_uses_gb_order_but_keeps_ni_half_width_mode():
    gb = _Edge(LineString([(0, 0), (1, 1)]), 5, 2, "os_open_rivers_gb", False)
    ni = _Edge(LineString([(0, 0), (1, 1)]), 5, 9, "ni_river_segment", True)
    roi = _Edge(LineString([(0, 0), (1, 1)]), 1, 1, "epa_river_network_routes_ie", True)

    assert _preview_radius_for_edge(gb) == 5
    assert _preview_radius_for_edge(ni) == _preview_radius_for_half_width(9)
    assert _preview_radius_for_edge(roi) == 0


def _test_manifest(**axis_scale: float) -> dict:
    scale_x = axis_scale.get("x", 1.0)
    scale_z = axis_scale.get("z", 1.0)
    return {
        "tile_size": 512,
        "georeferencing": {
            "bng_min_easting": 0,
            "bng_max_easting": 1000,
            "bng_min_northing": 0,
            "bng_max_northing": 1000,
        },
        "world": {"width": 100, "depth": 100, "padded_width": 512, "padded_depth": 512},
        "height": {"path": "height", "extension": ".r16"},
        "axis_scale": {"x": scale_x, "z": scale_z},
    }


def test_horizontal_axis_scale_averages_xz_and_floors_at_one():
    assert _horizontal_axis_scale({}) == 1.0
    assert _horizontal_axis_scale({"axis_scale": {"x": 2.0, "z": 2.0}}) == 2.0
    assert _horizontal_axis_scale({"axis_scale": {"x": 2.0, "z": 1.0}}) == 1.5
    assert _horizontal_axis_scale({"axis_scale": {"x": 0.5, "z": 0.5}}) == 1.0


def test_apply_axis_scale_to_half_widths_doubles_for_2x_world():
    edges = [
        _Edge(LineString([(0, 0), (1, 1)]), 1, 2),
        _Edge(LineString([(0, 0), (1, 1)]), 4, 10),
    ]
    _apply_axis_scale_to_half_widths(edges, 2.0)
    assert [edge.half_width for edge in edges] == [4, 20]


def test_apply_output_width_scale_thins_generated_rivers_to_one_third():
    edges = [
        _Edge(LineString([(0, 0), (1, 1)]), 1, 1),
        _Edge(LineString([(0, 0), (1, 1)]), 2, 4),
        _Edge(LineString([(0, 0), (1, 1)]), 5, 80),
    ]
    _apply_output_width_scale(edges)
    assert [edge.half_width for edge in edges] == [1, 1, 13]


def test_strahler_keeps_gb_orders_when_irish_source_orders_are_present(tmp_path):
    gb = [
        _InputLine(LineString([(0, 100), (50, 50)]), None, "os_open_rivers_gb", "N1", "N2", "in direction"),
        _InputLine(LineString([(100, 100), (50, 50)]), None, "os_open_rivers_gb", "N3", "N2", "in direction"),
        _InputLine(LineString([(50, 50), (50, 0)]), None, "os_open_rivers_gb", "N2", "N4", "in direction"),
    ]
    # Degenerate a==b edge used to misalign zip(lines, raw_edges) before the fix.
    degen = _InputLine(LineString([(0, 0), (1, 1)]), None, "os_open_rivers_gb", "SAME", "SAME", "in direction")
    ie = [
        _InputLine(LineString([(200, 100), (200, 0)]), 7, "epa_river_network_routes_ie", None, None, None),
        _InputLine(LineString([(210, 80), (200, 40)]), 6, "epa_river_network_routes_ie", None, None, None),
        _InputLine(LineString([(190, 80), (200, 40)]), 6, "epa_river_network_routes_ie", None, None, None),
        _InputLine(LineString([(200, 40), (200, 0)]), 7, "epa_river_network_routes_ie", None, None, None),
    ]

    result = _strahler_widths(gb + [degen] + ie, _test_manifest(x=1.0, z=1.0), tmp_path)
    gb_orders = sorted({edge.order for edge in result.edges if edge.source_dataset == "os_open_rivers_gb"})
    ie_orders = sorted({edge.order for edge in result.edges if edge.source_dataset == "epa_river_network_routes_ie"})

    assert max(gb_orders) >= 2
    assert ie_orders == [6, 7]


def test_strahler_scales_half_widths_with_axis_scale(tmp_path):
    lines = [
        _InputLine(LineString([(0, 100), (50, 50)]), None, "os_open_rivers_gb", "N1", "N2", "in direction"),
        _InputLine(LineString([(100, 100), (50, 50)]), None, "os_open_rivers_gb", "N3", "N2", "in direction"),
        _InputLine(LineString([(50, 50), (50, 0)]), None, "os_open_rivers_gb", "N2", "N4", "in direction"),
    ]
    one_x = _strahler_widths(lines, _test_manifest(x=1.0, z=1.0), tmp_path)
    two_x = _strahler_widths(lines, _test_manifest(x=2.0, z=2.0), tmp_path)

    assert [edge.order for edge in one_x.edges] == [edge.order for edge in two_x.edges]
    assert [edge.half_width for edge in one_x.edges] == [1, 1, 1]
    assert [edge.half_width for edge in two_x.edges] == [1, 1, 2]
