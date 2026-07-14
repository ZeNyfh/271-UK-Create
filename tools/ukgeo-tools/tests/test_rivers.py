from __future__ import annotations

import geopandas as gpd
from shapely.geometry import LineString, MultiLineString

from ukgeo.rivers import _Edge, _coerce_source_order, _extract_lines, _normalize_computed_order_to_source_scale, _thin_top_order_half_widths


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


def test_thin_top_order_half_widths_scales_top_four_order_levels():
    edges = [
        _Edge(LineString([(0, 0), (1, 1)]), 8, 20),
        _Edge(LineString([(0, 0), (1, 1)]), 7, 18),
        _Edge(LineString([(0, 0), (1, 1)]), 6, 16),
        _Edge(LineString([(0, 0), (1, 1)]), 5, 14),
        _Edge(LineString([(0, 0), (1, 1)]), 4, 12),
    ]

    _thin_top_order_half_widths(edges)

    assert [edge.half_width for edge in edges] == [10, 12, 13, 12, 12]


def test_normalize_computed_order_to_source_scale_compresses_to_source_max():
    assert _normalize_computed_order_to_source_scale(8, 8, 7) == 7
    assert _normalize_computed_order_to_source_scale(7, 8, 7) == 6
    assert _normalize_computed_order_to_source_scale(6, 8, 7) == 5
    assert _normalize_computed_order_to_source_scale(5, 8, 7) == 4
    assert _normalize_computed_order_to_source_scale(3, 8, 7) == 3
