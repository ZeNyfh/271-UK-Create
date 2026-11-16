from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pytest

from ukgeo.manifest import write_manifest
from ukgeo.river_stations import (
    ALONG_LIMIT,
    BANK_CARVE_REACH,
    CROSS_PAD,
    CellReach,
    MAX_UINT16,
    Station,
    StationGrid,
    bake_cell_reaches,
    TileMaskLookup,
    bake_river_stations,
    bake_river_stations_standalone,
    build_stations,
    flow_azimuth_u8,
    merge_reaches,
    metres_to_minecraft_y,
    ownership_hi_lo,
    rasterize_stations,
    reaches_from_edges,
    smooth_longitudinal_profile,
)
import ukgeo.river_stations as _river_stations
from ukgeo.tiles import (
    HEIGHT_NODATA,
    iter_packable_layers,
    pack_manifest_regions,
    r16_extension,
    read_layer_tile,
    read_r16_tile,
    read_u16_tile,
    read_u32_tile,
    read_u8_tile,
    region_extension,
    river_typed_layer,
    u16_extension,
    u32_extension,
    u8_extension,
    write_r16_tile,
    write_u32_tile,
    write_u8_tile,
)


def _station(**overrides) -> Station:
    data = dict(
        reach_id=1,
        station_id=0,
        order=1,
        x=8.5,
        z=8.5,
        tx=1.0,
        tz=0.0,
        half_width=2,
        water_metres=10.0,
        water_y=70,
        azimuth=3,
    )
    data.update(overrides)
    return Station(**data)


def _assert_order_independent(stations: list[Station], depth: int = 16, width: int = 16):
    forward = rasterize_stations(stations, depth, width)
    backward = rasterize_stations(list(reversed(stations)), depth, width)
    assert np.array_equal(forward.reach_id, backward.reach_id)
    assert np.array_equal(forward.station_id, backward.station_id)
    assert np.array_equal(forward.water_y, backward.water_y)
    assert np.array_equal(forward.azimuth, backward.azimuth)
    return forward


def test_reverse_reach_order_identical_ownership():
    # Closer station beats a higher-order neighbour.
    nearer = _assert_order_independent(
        [
            _station(reach_id=5, order=1, water_y=70, azimuth=3),
            _station(reach_id=1, order=9, z=9.2, water_y=99, azimuth=9),
        ]
    )
    assert int(nearer.reach_id[8, 8]) == 5
    assert int(nearer.station_id[8, 8]) == 0
    assert int(nearer.water_y[8, 8]) == 70

    # Same distance: higher Strahler wins over a lower reach id.
    higher = _assert_order_independent(
        [
            _station(reach_id=1, order=2, water_y=70, azimuth=1),
            _station(reach_id=2, order=6, water_y=81, azimuth=6),
        ]
    )
    assert int(higher.reach_id[8, 8]) == 2
    assert int(higher.water_y[8, 8]) == 81

    # Same distance and order: lower reach id wins.
    lower_reach = _assert_order_independent(
        [
            _station(reach_id=4, order=5, water_y=88, azimuth=4),
            _station(reach_id=1, order=5, water_y=70, azimuth=1),
        ]
    )
    assert int(lower_reach.reach_id[8, 8]) == 1
    assert int(lower_reach.water_y[8, 8]) == 70

    # Same distance, order, and reach: lower station id wins.
    lower_station = _assert_order_independent(
        [
            _station(reach_id=1, station_id=3, order=5, water_y=88, azimuth=3),
            _station(reach_id=1, station_id=0, order=5, water_y=70, azimuth=0),
        ]
    )
    assert int(lower_station.station_id[8, 8]) == 0
    assert int(lower_station.water_y[8, 8]) == 70
    assert nearer.reach_id.dtype == np.dtype("<u4")
    assert nearer.station_id.dtype == np.dtype("<u2")

    # 65536 aliases to 0 in 16 bits, which would beat reach 1 and look like no river.
    aliased = _assert_order_independent(
        [
            _station(reach_id=65536, order=4, water_y=90, azimuth=2),
            _station(reach_id=1, order=4, water_y=70, azimuth=1),
        ]
    )
    assert int(aliased.reach_id[8, 8]) == 1
    assert int(aliased.water_y[8, 8]) == 70

    # Both ids share a zero low 16 bits, so a 16-bit key would tie and depend on stamp order.
    wide_tie = _assert_order_independent(
        [
            _station(reach_id=131072, order=4, water_y=90, azimuth=2),
            _station(reach_id=65536, order=4, water_y=70, azimuth=1),
        ]
    )
    assert int(wide_tie.reach_id[8, 8]) == 65536
    assert int(wide_tie.water_y[8, 8]) == 70

    # A nearer uint32 reach still beats a smaller id that is farther away.
    nearer_wide = _assert_order_independent(
        [
            _station(reach_id=345610, order=1, water_y=70, azimuth=3),
            _station(reach_id=1, order=9, z=9.2, water_y=99, azimuth=9),
        ]
    )
    assert int(nearer_wide.reach_id[8, 8]) == 345610
    assert int(nearer_wide.water_y[8, 8]) == 70

    # Full reaches: reversing input order keeps reach ids, ownership, and water Y.
    points = ((4.5, 12.5), (20.5, 12.5))
    low = CellReach(points, order=2, half_width=2)
    high = CellReach(points, order=6, half_width=2)
    depth = width = 24
    height = np.full((depth, width), 500, dtype=np.int16)
    mask = np.zeros((depth, width), dtype=np.uint8)
    forward = bake_cell_reaches([low, high], height, mask)
    backward = bake_cell_reaches([high, low], height, mask)
    assert np.array_equal(forward.reach_id, backward.reach_id)
    assert np.array_equal(forward.station_id, backward.station_id)
    assert np.array_equal(forward.water_y, backward.water_y)
    assert np.array_equal(forward.azimuth, backward.azimuth)
    high_id = next(station.reach_id for station in forward.stations if station.order == 6)
    low_id = next(station.reach_id for station in forward.stations if station.order == 2)
    assert high_id > low_id
    assert int(forward.reach_id[12, 12]) == high_id
    assert int(forward.reach_id[0, 0]) == 0
    assert int(forward.water_y[0, 0]) == HEIGHT_NODATA
    restamped = _assert_order_independent(forward.stations, depth, width)
    assert np.array_equal(restamped.reach_id, forward.reach_id)
    assert np.array_equal(restamped.water_y, forward.water_y)


def test_lower_bank_wins_and_profile_keeps_cascade():
    raw = np.array([50, 50, 55, 50, 50, 46, 45], dtype=np.float64)
    profile = smooth_longitudinal_profile(raw)
    assert np.all(profile <= raw + 1e-9)
    assert np.all(np.diff(profile) <= 1e-9)
    assert profile[2] <= 50 + 1e-9
    assert profile[4] == pytest.approx(50)
    assert profile[4] - profile[5] >= 2.5 - 1e-9

    depth, width = 40, 40
    height = np.full((depth, width), HEIGHT_NODATA, dtype=np.int16)
    height[19:23, :] = 800  # left bank, 80 m
    height[10:14, :] = 500  # right bank, 50 m
    height[10:14, 8:16] = 550  # downstream rise that water must not follow
    height[10:14, 24:] = 460  # 4 m cascade
    height[15:18, :] = 100  # channel; masked so it cannot win
    mask = np.zeros((depth, width), dtype=np.uint8)
    mask[15:18, :] = 255
    baked = bake_cell_reaches([CellReach(((2.0, 16.5), (36.0, 16.5)), order=4, half_width=1)], height, mask)
    ordered = sorted((station for station in baked.stations if math_isfinite(station.water_metres)), key=lambda station: station.x)
    metres = np.array([station.water_metres for station in ordered])
    ys = np.array([station.water_y for station in ordered])
    assert ordered[0].station_id == 0
    assert np.all(np.diff(metres) <= 1e-6)
    assert np.all(np.diff(ys) <= 0)
    early = [station for station in ordered if station.x < 6]
    rise = [station for station in ordered if 10 <= station.x <= 14]
    late = [station for station in ordered if station.x > 26]
    assert early and rise and late
    assert all(station.water_metres == pytest.approx(50) for station in early)
    assert all(station.water_y == metres_to_minecraft_y(50) for station in early)
    assert all(station.water_metres <= 50 + 1e-6 for station in rise)
    assert all(station.water_y <= metres_to_minecraft_y(50) for station in rise)
    assert all(station.water_metres == pytest.approx(46) for station in late)
    assert all(station.water_y == metres_to_minecraft_y(46) for station in late)
    assert early[-1].water_metres - late[0].water_metres >= 2.5 - 1e-6
    assert metres_to_minecraft_y(80) != early[0].water_y


def math_isfinite(value: float) -> bool:
    return value == value and abs(value) != float("inf")


def test_same_station_water_y_across_width():
    depth, width = 40, 40
    height = np.full((depth, width), HEIGHT_NODATA, dtype=np.int16)
    height[:, 10:14] = 800  # left bank
    height[:, 19:23] = 500  # right bank, 50 m
    height[20:, 19:23] = 300  # downstream cascade to 30 m
    height[:, 15:18] = 100
    mask = np.zeros((depth, width), dtype=np.uint8)
    mask[:, 15:18] = 255
    baked = bake_cell_reaches([CellReach(((16.5, 2.0), (16.5, 34.0)), order=5, half_width=1)], height, mask)
    lookup = {(station.reach_id, station.station_id): station for station in baked.stations}
    owned = baked.reach_id != 0
    assert bool(owned.any())
    counts: dict[tuple[int, int], int] = {}
    water_values: set[int] = set()
    for z, x in zip(*np.nonzero(owned)):
        key = (int(baked.reach_id[z, x]), int(baked.station_id[z, x]))
        assert int(baked.water_y[z, x]) == lookup[key].water_y
        assert int(baked.azimuth[z, x]) == lookup[key].azimuth == flow_azimuth_u8(0.0, 1.0)
        counts[key] = counts.get(key, 0) + 1
        water_values.add(int(baked.water_y[z, x]))
    assert max(counts.values()) >= 5
    assert len(water_values) >= 2


def test_metres_to_minecraft_y_golden():
    assert metres_to_minecraft_y(0) == 64
    assert metres_to_minecraft_y(10) == 67
    assert metres_to_minecraft_y(50) == 79
    assert metres_to_minecraft_y(46) == 78
    assert metres_to_minecraft_y(80) == 87
    assert metres_to_minecraft_y(100) == 92
    assert metres_to_minecraft_y(120) == 96
    assert metres_to_minecraft_y(200) == 118
    assert metres_to_minecraft_y(435) == 188
    assert metres_to_minecraft_y(750) == 289
    assert metres_to_minecraft_y(-5) == 62
    assert metres_to_minecraft_y(0, sea_level_y=63) == 63
    assert metres_to_minecraft_y(10, height_scale=1.0, lowland_extra_scale=0.0, highland_scale=1.0) == 74
    assert flow_azimuth_u8(1, 0) == 0
    assert flow_azimuth_u8(0, 1) == 64
    assert flow_azimuth_u8(-1, 0) == 128
    assert flow_azimuth_u8(0, -1) == 192


def test_bake_requires_edges(tmp_path: Path):
    with pytest.raises(ValueError, match="vector edges"):
        bake_river_stations(
            tmp_path,
            manifest_path=tmp_path / "manifest.json",
            edges=[],
            river_mask=np.zeros((1, 1), dtype=np.uint8),
        )


class _Line:
    def __init__(self, coords):
        self.coords = list(coords)


class _Edge:
    def __init__(self, coords, order, half_width):
        self.line = _Line(coords)
        self.order = order
        self.half_width = half_width


def test_bake_river_stations_writes_schema_and_tiles(tmp_path: Path):
    tile_size = 32
    height = np.full((tile_size, tile_size), 400, dtype=np.int16)
    write_r16_tile(tmp_path / "height" / f"000_000{r16_extension()}", height, tile_size)
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
        },
        "georeferencing": {
            "bng_min_easting": 0.0,
            "bng_min_northing": 0.0,
            "bng_max_easting": float(tile_size),
            "bng_max_northing": float(tile_size),
        },
        "height": {
            "path": "height",
            "extension": r16_extension(),
            "dtype": "int16_le",
            "nodata": HEIGHT_NODATA,
            "sea_level_y": 64,
        },
        "rivers": {"path": "water/rivers", "dtype": "uint8"},
    }
    manifest_path = tmp_path / "manifest.json"
    write_manifest(manifest_path, manifest)
    edge = _Edge([(4.0, 15.5), (28.0, 15.5)], order=3, half_width=1)
    bake_river_stations(
        tmp_path,
        manifest_path=manifest_path,
        edges=[edge],
        river_mask=np.zeros((tile_size, tile_size), dtype=np.uint8),
    )
    updated = json.loads(manifest_path.read_text())
    rivers = updated["rivers"]
    assert rivers["station_schema_version"] == 1
    assert rivers["reach_id_dtype"] == "uint32_le"
    assert rivers["reach_id_extension"] == u32_extension()
    assert rivers["station_id_dtype"] == "uint16_le"
    assert rivers["station_id_extension"] == u16_extension()
    assert rivers["water_y_dtype"] == "int16_le"
    assert rivers["flow_azimuth_dtype"] == "uint8"
    reach = read_u32_tile(tmp_path / "water" / "river_reach_id" / f"000_000{u32_extension()}", tile_size)
    station = read_u16_tile(tmp_path / "water" / "river_station_id" / f"000_000{u16_extension()}", tile_size)
    water = read_r16_tile(tmp_path / "water" / "river_water_y" / f"000_000{r16_extension()}", tile_size)
    azimuth = read_u8_tile(tmp_path / "water" / "river_flow_azimuth" / f"000_000{u8_extension()}", tile_size)
    assert int(reach[16, 16]) >= 1
    assert int(water[16, 16]) == metres_to_minecraft_y(40)
    owned = reach == reach[16, 16]
    same_station = owned & (station == station[16, 16])
    assert int(same_station.sum()) >= 5
    assert np.all(water[same_station] == water[16, 16])
    assert int(azimuth[16, 16]) == flow_azimuth_u8(1.0, 0.0)


def test_u32_reach_tile_roundtrip_and_region_pack(tmp_path: Path):
    assert region_extension("uint32_le") == ".u32rg"
    assert region_extension("uint16_le") == ".u16rg"
    assert u32_extension() == ".u32"
    assert u16_extension() == ".u16"
    tile_size = 32
    arr = np.zeros((tile_size, tile_size), dtype="<u4")
    arr[1, 2] = 9
    arr[3, 4] = 65536  # truncates to 0 if stored as uint16
    arr[5, 6] = 345610
    raw = tmp_path / "water" / "river_reach_id" / f"000_000{u32_extension()}"
    write_u32_tile(raw, arr, tile_size)
    assert np.array_equal(read_u32_tile(raw, tile_size), arr)
    write_u32_tile(raw, np.zeros((tile_size, tile_size), dtype="<u4"), tile_size)
    assert not raw.exists()
    assert np.array_equal(read_u32_tile(raw, tile_size), np.zeros((tile_size, tile_size), dtype="<u4"))
    write_u32_tile(raw, arr, tile_size)

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
        },
        "height": {
            "path": "height",
            "extension": r16_extension(),
            "dtype": "int16_le",
            "nodata": HEIGHT_NODATA,
            "sea_level_y": 64,
        },
        "rivers": {
            "path": "water/rivers",
            "extension": u8_extension(),
            "dtype": "uint8",
            "reach_id_path": "water/river_reach_id",
            "reach_id_extension": u32_extension(),
            "reach_id_dtype": "uint32_le",
        },
    }
    pack_manifest_regions(tmp_path, manifest, region_tiles=1, delete_raw=True)
    assert manifest["rivers"]["reach_id_region_extension"] == ".u32rg"
    assert manifest["rivers"]["reach_id_storage"] == "regions"
    assert not raw.exists()
    layer = river_typed_layer(manifest["rivers"], "reach_id_path", "reach_id", "uint32_le")
    assert np.array_equal(read_layer_tile(tmp_path, layer, 0, 0, tile_size), arr)
    assert np.array_equal(
        read_layer_tile(tmp_path, layer, 1, 0, tile_size),
        np.zeros((tile_size, tile_size), dtype="<u4"),
    )
    packed = [layer for layer in iter_packable_layers(manifest) if layer.get("dtype") == "uint32_le"]
    assert packed and packed[0]["path"] == "water/river_reach_id"
    assert not any(layer.get("path") == "water/river_reach_id" and layer.get("dtype") == "uint16_le" for layer in iter_packable_layers(manifest))


def test_merge_degree2_chain_and_stop_at_junctions():
    chain = [CellReach(((float(i), 0.0), (float(i + 1), 0.0)), order=4, half_width=2) for i in range(5)]
    merged = merge_reaches(chain)
    assert len(merged) == 1
    assert merged[0].points[0] == (0.0, 0.0)
    assert merged[0].points[-1] == (5.0, 0.0)
    assert len(merged[0].points) == 6
    assert merged[0].order == 4 and merged[0].half_width == 2
    assert merge_reaches(list(reversed(chain)))[0].points == merged[0].points

    short = [CellReach(((i * 0.4, 1.0), ((i + 1) * 0.4, 1.0)), order=2, half_width=1) for i in range(20)]
    short_merged = merge_reaches(short)
    assert len(short_merged) == 1
    assert short_merged[0].points[-1][0] == pytest.approx(8.0)
    assert len(short_merged[0].points) == 21

    near = [
        CellReach(((0.0, 2.0), (10.0, 2.0)), order=2, half_width=1),
        CellReach(((10.5, 2.0), (20.0, 2.0)), order=2, half_width=1),
    ]
    far = [
        CellReach(((0.0, 2.0), (10.0, 2.0)), order=2, half_width=1),
        CellReach(((10.51, 2.0), (20.0, 2.0)), order=2, half_width=1),
    ]
    assert len(merge_reaches(near)) == 1
    assert len(merge_reaches(far)) == 2

    assert len(merge_reaches([
        CellReach(((0.0, 3.0), (5.0, 3.0)), order=2, half_width=1),
        CellReach(((5.0, 3.0), (9.0, 3.0)), order=4, half_width=1),
    ])) == 2
    assert len(merge_reaches([
        CellReach(((0.0, 3.5), (5.0, 3.5)), order=3, half_width=1),
        CellReach(((5.0, 3.5), (9.0, 3.5)), order=3, half_width=2),
    ])) == 2

    bend = merge_reaches([
        CellReach(((0.0, 4.0), (10.0, 4.0)), order=3, half_width=1),
        CellReach(((10.0, 4.0), (20.0, 14.0)), order=3, half_width=1),
    ])
    assert len(bend) == 1
    corner = merge_reaches([
        CellReach(((0.0, 5.0), (10.0, 5.0)), order=3, half_width=1),
        CellReach(((10.0, 5.0), (10.0, 18.0)), order=3, half_width=1),
    ])
    assert len(corner) == 2
    reversal = merge_reaches([
        CellReach(((0.0, 6.0), (10.0, 6.0)), order=3, half_width=1),
        CellReach(((10.0, 6.0), (0.0, 6.0)), order=3, half_width=1),
    ])
    assert len(reversal) == 2

    junction = merge_reaches([
        CellReach(((0.0, 7.0), (10.0, 7.0)), order=5, half_width=2),
        CellReach(((10.0, 7.0), (20.0, 7.0)), order=5, half_width=2),
        CellReach(((10.0, 0.0), (10.0, 7.0)), order=5, half_width=2),
    ])
    assert len(junction) == 3


def test_reaches_from_edges_merges_before_reach_ids():
    span = 64.0
    manifest = {
        "georeferencing": {
            "bng_min_easting": 0.0,
            "bng_min_northing": 0.0,
            "bng_max_easting": span,
            "bng_max_northing": span,
        },
        "world": {"width": 64, "depth": 64},
    }
    edges = [_Edge([(float(i), 20.0), (float(i + 1), 20.0)], order=3, half_width=1) for i in range(30)]
    edges.append(_Edge([(30.0, 10.0), (30.0, 20.0)], order=3, half_width=1))
    reaches = reaches_from_edges(edges, manifest)
    assert len(reaches) == 2
    assert len(reaches) <= 65534
    long = max(reaches, key=lambda reach: len(reach.points))
    short = min(reaches, key=lambda reach: len(reach.points))
    assert len(long.points) == 31
    assert len(short.points) == 2
    assert long.order == 3 and long.half_width == 1


def test_merge_colinear_edges_under_uint16():
    count = MAX_UINT16 + 5
    reaches = [CellReach(((float(i), 0.0), (float(i + 1), 0.0)), order=4, half_width=1) for i in range(count)]
    merged = merge_reaches(reaches)
    assert count > 65534
    assert len(merged) == 1
    assert len(merged) <= 65534
    assert merged[0].points[0] == (0.0, 0.0)
    assert merged[0].points[-1] == (float(count), 0.0)
    assert len(merged[0].points) == count + 1
    assert merged[0].order == 4 and merged[0].half_width == 1
    prefix = reaches[:200]
    assert merge_reaches(prefix)[0].points == merge_reaches(list(reversed(prefix)))[0].points
    stations = build_stations(merge_reaches(reaches[:32]), lambda _x, _z: 40.0, lambda _x, _z: False)
    assert stations
    assert {station.reach_id for station in stations} == {1}
    assert all(station.reach_id <= 65534 for station in stations)



def test_skip_existing_keeps_prewritten_station_tile(tmp_path: Path):
    tile_size = 32
    height = np.full((tile_size, tile_size), 400, dtype=np.int16)
    write_r16_tile(tmp_path / "height" / f"000_000{r16_extension()}", height, tile_size)
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
        },
        "georeferencing": {
            "bng_min_easting": 0.0,
            "bng_min_northing": 0.0,
            "bng_max_easting": float(tile_size),
            "bng_max_northing": float(tile_size),
        },
        "height": {
            "path": "height",
            "extension": r16_extension(),
            "dtype": "int16_le",
            "nodata": HEIGHT_NODATA,
            "sea_level_y": 64,
        },
        "rivers": {"path": "water/rivers", "extension": u8_extension(), "dtype": "uint8"},
    }
    manifest_path = tmp_path / "manifest.json"
    write_manifest(manifest_path, manifest)
    # Southbound so flow_azimuth is non-zero. An eastbound reach stamps azimuth 0,
    # and write_u8_tile omits an all-zero tile, which must not count as complete.
    edge = _Edge([(16.0, 4.0), (16.0, 28.0)], order=3, half_width=1)
    bake_river_stations(
        tmp_path,
        manifest_path=manifest_path,
        edges=[edge],
        river_mask=np.zeros((tile_size, tile_size), dtype=np.uint8),
    )
    reach_path = tmp_path / "water" / "river_reach_id" / f"000_000{u32_extension()}"
    assert (tmp_path / "water" / "river_station_id" / f"000_000{u16_extension()}").exists()
    assert (tmp_path / "water" / "river_water_y" / f"000_000{r16_extension()}").exists()
    assert (tmp_path / "water" / "river_flow_azimuth" / f"000_000{u8_extension()}").exists()
    reach = np.array(read_u32_tile(reach_path, tile_size), copy=True)
    assert int(reach[0, 0]) != 424242
    reach[0, 0] = np.uint32(424242)
    write_u32_tile(reach_path, reach, tile_size)
    bake_river_stations_standalone(
        tmp_path,
        manifest_path=manifest_path,
        edges=[edge],
        skip_existing=True,
    )
    assert int(read_u32_tile(reach_path, tile_size)[0, 0]) == 424242
    updated = json.loads(manifest_path.read_text())
    assert updated["rivers"]["station_schema_version"] == 1


def test_tile_mask_lookup_reads_and_evicts(tmp_path: Path):
    tile_size = 8
    painted = np.zeros((tile_size, tile_size), dtype=np.uint8)
    painted[2, 3] = 255
    write_u8_tile(tmp_path / "water" / "rivers" / f"000_000{u8_extension()}", painted, tile_size)
    width = tile_size * 40
    manifest = {
        "tile_size": tile_size,
        "world": {
            "width": width,
            "depth": tile_size,
            "padded_width": width,
            "padded_depth": tile_size,
        },
        "rivers": {"path": "water/rivers", "extension": u8_extension(), "dtype": "uint8"},
    }
    lookup = TileMaskLookup(tmp_path, manifest)
    assert lookup(3, 2) is True
    assert lookup(0, 0) is False
    assert lookup(-1, 0) is False
    assert lookup(width, 0) is False
    for index in range(1, 40):
        assert lookup(index * tile_size, 0) is False
    assert len(lookup._cache) <= 32
    assert lookup(3, 2) is True


def test_parallel_build_stations_matches_sequential(monkeypatch: pytest.MonkeyPatch):
    reaches = [
        CellReach(((0.0, 8.0), (14.0, 8.0)), order=4, half_width=2),
        CellReach(((1.0, 1.0), (1.0, 20.0)), order=2, half_width=1),
        CellReach(((4.0, 4.0), (18.0, 9.0), (18.0, 16.0)), order=6, half_width=3),
    ]

    def height_at(x: int, z: int) -> float:
        return 48.0 - 0.15 * float(x) + 0.05 * float(z)

    def mask_at(x: int, z: int) -> bool:
        return z == 8 and 0 <= x <= 14

    sequential = build_stations(reaches, height_at, mask_at, workers=1)
    import ukgeo.river_stations as river_stations_mod

    entered: dict[str, object] = {}
    original = river_stations_mod._ForkWorkers.__enter__

    def _enter(self):
        entered["workers"] = self.workers
        entered["enabled"] = self.enabled
        return original(self)

    monkeypatch.setattr(river_stations_mod._ForkWorkers, "__enter__", _enter)
    parallel = build_stations(list(reversed(reaches)), height_at, mask_at, workers=2)
    assert entered.get("workers") == 2
    assert entered.get("enabled") is True
    assert len({station.reach_id for station in sequential}) >= 2
    assert parallel == sequential


def test_cross_track_ownership_on_bend_not_radial():
    """Perpendicular corner stations: |perp| splits axes; Euclidean would tie and let order fan."""
    east = _station(
        reach_id=1,
        station_id=0,
        order=9,
        x=10.5,
        z=10.5,
        tx=1.0,
        tz=0.0,
        half_width=2,
        water_y=70,
        azimuth=0,
    )
    north = _station(
        reach_id=2,
        station_id=0,
        order=1,
        x=10.5,
        z=10.5,
        tx=0.0,
        tz=1.0,
        half_width=2,
        water_y=80,
        azimuth=64,
    )
    grid = _assert_order_independent([east, north], depth=24, width=24)
    # East of corner on east-flow centerline: smaller |perp| to east station.
    assert int(grid.reach_id[10, 11]) == 1
    assert int(grid.station_id[10, 11]) == 0
    assert int(grid.water_y[10, 11]) == 70
    # North of corner on north-flow centerline: smaller |perp| to north station
    # even though east has much higher Strahler order.
    assert int(grid.reach_id[11, 10]) == 2
    assert int(grid.water_y[11, 10]) == 80

    # Bank cell farther in Euclidean from north station but with smaller |perp|
    # to north than to a nearby east-flow station offset upstream.
    upstream = _station(
        reach_id=3,
        station_id=0,
        order=9,
        x=8.5,
        z=10.5,
        tx=1.0,
        tz=0.0,
        half_width=2,
        water_y=60,
        azimuth=0,
    )
    bend = _station(
        reach_id=4,
        station_id=0,
        order=1,
        x=10.5,
        z=10.5,
        tx=0.0,
        tz=1.0,
        half_width=2,
        water_y=90,
        azimuth=64,
    )
    bent = _assert_order_independent([upstream, bend], depth=24, width=24)
    # Cell (10, 11) = (10.5, 11.5): Euclidean to upstream ≈ 2.24, to bend = 1.0,
    # but also |perp| upstream=1 vs bend=0 — bend must win on cross-track.
    assert int(bent.reach_id[11, 10]) == 4
    assert int(bent.water_y[11, 10]) == 90


_PREVIEW_STATION_PALETTE = (
    (230, 25, 75),
    (60, 180, 75),
    (255, 225, 25),
    (0, 130, 200),
    (245, 130, 48),
    (145, 30, 180),
    (70, 240, 240),
    (240, 50, 230),
    (210, 245, 60),
    (250, 190, 212),
    (0, 128, 128),
    (220, 190, 255),
)


def _stamp_one_euclidean(
    sx: float,
    sz: float,
    tx: float,
    tz: float,
    half_width: int,
    order: int,
    reach_id: int,
    station_id: int,
    water_y: int,
    azimuth_value: int,
    reach: np.ndarray,
    station_out: np.ndarray,
    water: np.ndarray,
    azimuth: np.ndarray,
    best_hi: np.ndarray,
    best_lo: np.ndarray,
    *,
    width: int,
    depth: int,
) -> None:
    """Test-only stamp: same corridor as production, ownership distance = hypot."""
    tx, tz = _river_stations._unit_tangent(tx, tz)
    cross_lim = float(max(0, half_width) + BANK_CARVE_REACH) + CROSS_PAD
    x0 = max(0, int(np.floor(sx - cross_lim - 1.0)))
    x1 = min(width, int(np.ceil(sx + cross_lim + 1.0)))
    z0 = max(0, int(np.floor(sz - cross_lim - 1.0)))
    z1 = min(depth, int(np.ceil(sz + cross_lim + 1.0)))
    if x0 >= x1 or z0 >= z1:
        return
    xs = np.arange(x0, x1, dtype=np.float64) + 0.5
    zs = np.arange(z0, z1, dtype=np.float64) + 0.5
    dx = xs[None, :] - sx
    dz = zs[:, None] - sz
    along = dx * tx + dz * tz
    perp = dx * (-tz) + dz * tx
    inside = (np.abs(along) <= ALONG_LIMIT + 1e-6) & (np.abs(perp) <= cross_lim + 1e-6)
    if not bool(inside.any()):
        return
    dist_milli = np.floor(np.hypot(dx, dz) * 1000.0 + 0.5).astype(np.int64)
    np.clip(dist_milli, 0, 0xFFFFFF, out=dist_milli)
    order_hi, keys_lo = ownership_hi_lo(0, order, reach_id, station_id)
    keys_hi = (dist_milli.astype(np.uint64) << np.uint64(8)) | order_hi
    hh = z1 - z0
    ww = x1 - x0
    best_hi_view = best_hi[z0 : z0 + hh, x0 : x0 + ww]
    best_lo_view = best_lo[z0 : z0 + hh, x0 : x0 + ww]
    better = inside & ((keys_hi < best_hi_view) | ((keys_hi == best_hi_view) & (keys_lo < best_lo_view)))
    if not bool(better.any()):
        return
    best_hi_view[better] = keys_hi[better]
    best_lo_view[better] = keys_lo
    reach[z0 : z0 + hh, x0 : x0 + ww][better] = np.uint32(int(reach_id) & 0xFFFFFFFF)
    station_out[z0 : z0 + hh, x0 : x0 + ww][better] = np.uint16(int(station_id) & 0xFFFF)
    water[z0 : z0 + hh, x0 : x0 + ww][better] = np.int16(water_y)
    azimuth[z0 : z0 + hh, x0 : x0 + ww][better] = np.uint8(azimuth_value)


def _rasterize_stations_euclidean(stations: list[Station], depth: int, width: int) -> StationGrid:
    reach = np.zeros((depth, width), dtype="<u4")
    station_id = np.zeros((depth, width), dtype="<u2")
    water = np.full((depth, width), HEIGHT_NODATA, dtype=np.int16)
    azimuth = np.zeros((depth, width), dtype=np.uint8)
    empty = np.uint64(np.iinfo(np.uint64).max)
    best_hi = np.full((depth, width), empty, dtype=np.uint64)
    best_lo = np.full((depth, width), empty, dtype=np.uint64)
    for station in stations:
        if not math_isfinite(station.water_metres):
            continue
        _stamp_one_euclidean(
            station.x,
            station.z,
            station.tx,
            station.tz,
            int(station.half_width),
            int(station.order),
            int(station.reach_id),
            int(station.station_id),
            int(station.water_y),
            int(station.azimuth),
            reach,
            station_id,
            water,
            azimuth,
            best_hi,
            best_lo,
            width=width,
            depth=depth,
        )
    return StationGrid(reach, station_id, water, azimuth)


def _colorize_station_ids(station_id: np.ndarray, reach_id: np.ndarray) -> np.ndarray:
    owned = reach_id != 0
    rgb = np.zeros((*station_id.shape, 3), dtype=np.uint8)
    palette = np.asarray(_PREVIEW_STATION_PALETTE, dtype=np.uint8)
    if owned.any():
        idx = station_id.astype(np.int64) % len(palette)
        rgb[owned] = palette[idx[owned]]
    return rgb


def _write_station_preview_png(path: Path, station_id: np.ndarray, reach_id: np.ndarray, *, target_px: int = 512) -> Path:
    from PIL import Image

    rgb = _colorize_station_ids(station_id, reach_id)
    image = Image.fromarray(rgb, mode="RGB")
    height, width = rgb.shape[:2]
    scale = max(1, int(target_px) // max(height, width))
    if scale > 1:
        image = image.resize((width * scale, height * scale), Image.NEAREST)
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path)
    return path


def _l_channel_stations() -> list[Station]:
    stations: list[Station] = []
    for i, x in enumerate((4.5, 5.5, 6.5, 7.5, 8.5, 9.5, 10.5)):
        stations.append(
            _station(
                reach_id=1,
                station_id=i,
                order=3,
                x=x,
                z=10.5,
                tx=1.0,
                tz=0.0,
                half_width=2,
                water_y=70 + i,
                azimuth=0,
            )
        )
    for j, z in enumerate((11.5, 12.5, 13.5, 14.5, 15.5, 16.5)):
        stations.append(
            _station(
                reach_id=1,
                station_id=7 + j,
                order=3,
                x=10.5,
                z=z,
                tx=0.0,
                tz=1.0,
                half_width=2,
                water_y=77 + j,
                azimuth=64,
            )
        )
    return stations


def _quarter_circle_stations(
    *,
    cx: float = 20.5,
    cz: float = 20.5,
    radius: float = 16.0,
    n_arc: int = 33,
    half_width: int = 2,
) -> list[Station]:
    """Eastbound into a quarter-circle bend, then northbound (smooth L)."""
    import math

    stations: list[Station] = []
    station_id = 0
    # West stub into the arc.
    for x in (cx - 4.0, cx - 3.0, cx - 2.0, cx - 1.0):
        stations.append(
            _station(
                reach_id=1,
                station_id=station_id,
                order=3,
                x=x,
                z=cz - radius,
                tx=1.0,
                tz=0.0,
                half_width=half_width,
                water_y=70 + station_id,
                azimuth=0,
            )
        )
        station_id += 1
    # theta -pi/2 -> 0: start (cx, cz-R) tangent +X, end (cx+R, cz) tangent +Z.
    for i in range(n_arc):
        theta = -0.5 * math.pi + (0.5 * math.pi) * (i / (n_arc - 1))
        x = cx + radius * math.cos(theta)
        z = cz + radius * math.sin(theta)
        # d/dtheta (cos, sin) = (-sin, cos); positive sweep → tangent (-sin, cos).
        tx = -math.sin(theta)
        tz = math.cos(theta)
        stations.append(
            _station(
                reach_id=1,
                station_id=station_id,
                order=3,
                x=x,
                z=z,
                tx=tx,
                tz=tz,
                half_width=half_width,
                water_y=70 + station_id,
                azimuth=flow_azimuth_u8(tx, tz),
            )
        )
        station_id += 1
    # North stub out of the arc.
    for z in (cz + 1.0, cz + 2.0, cz + 3.0, cz + 4.0):
        stations.append(
            _station(
                reach_id=1,
                station_id=station_id,
                order=3,
                x=cx + radius,
                z=z,
                tx=0.0,
                tz=1.0,
                half_width=half_width,
                water_y=70 + station_id,
                azimuth=64,
            )
        )
        station_id += 1
    return stations


def test_write_curved_channel_ownership_preview(tmp_path: Path):
    """Write PIL RGB previews: L channel plus Euclidean vs |perp| on a smooth curve."""
    out_dir = Path(__file__).resolve().parent / "output"
    out_dir.mkdir(parents=True, exist_ok=True)

    l_stations = _l_channel_stations()
    l_grid = rasterize_stations(l_stations, depth=24, width=24)
    assert int((l_grid.reach_id != 0).sum()) > 40
    l_png = _write_station_preview_png(
        out_dir / "river_station_ownership_L.png",
        l_grid.station_id,
        l_grid.reach_id,
    )
    note_path = out_dir / "river_station_ownership_L.txt"
    note_path.write_text(
        "L-shaped channel station ownership preview (cross-track |perp| ownership).\n"
        f"artifact={l_png}\n"
        f"owned_cells={int((l_grid.reach_id != 0).sum())}\n"
        "Colorized station_id palette, nearest-neighbor upscaled PNG via PIL.\n"
    )
    assert l_png.is_file()
    assert note_path.is_file()

    curve_stations = _quarter_circle_stations()
    depth = width = 48
    perp_grid = rasterize_stations(curve_stations, depth=depth, width=width)
    euclid_grid = _rasterize_stations_euclidean(curve_stations, depth=depth, width=width)
    assert int((perp_grid.reach_id != 0).sum()) > 100
    assert int((euclid_grid.reach_id != 0).sum()) > 100

    euclid_png = _write_station_preview_png(
        out_dir / "river_station_ownership_curve_euclidean.png",
        euclid_grid.station_id,
        euclid_grid.reach_id,
    )
    perp_png = _write_station_preview_png(
        out_dir / "river_station_ownership_curve_perp.png",
        perp_grid.station_id,
        perp_grid.reach_id,
    )
    curve_note = out_dir / "river_station_ownership_curve.txt"
    curve_note.write_text(
        "Quarter-circle channel ownership comparison.\n"
        f"euclidean={euclid_png} (hypot ownership, test helper)\n"
        f"perp={perp_png} (production |perp| via rasterize_stations)\n"
        f"owned_euclidean={int((euclid_grid.reach_id != 0).sum())}\n"
        f"owned_perp={int((perp_grid.reach_id != 0).sum())}\n"
    )
    assert euclid_png.is_file()
    assert perp_png.is_file()
    assert curve_note.is_file()
