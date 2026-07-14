from __future__ import annotations

import geopandas as gpd
from shapely.geometry import LineString, MultiLineString

from ukgeo.rivers import _coerce_source_order, _extract_lines


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
