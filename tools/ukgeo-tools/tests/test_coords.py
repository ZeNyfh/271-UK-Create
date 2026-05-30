from ukgeo.coords import (
    DEFAULT_MINECRAFT_MIN_X,
    DEFAULT_MINECRAFT_MIN_Z,
    NOTTINGHAM_ORIGIN_BNG_EASTING,
    NOTTINGHAM_ORIGIN_BNG_NORTHING,
    WorldBounds,
    minecraft_min_for_bng_origin,
    minecraft_to_tile_cell,
    tile_filename,
)


def test_tile_filename():
    assert tile_filename(1, 2, ".r16.gz") == "001_002.r16.gz"


def test_minecraft_to_tile_cell():
    bounds = WorldBounds.from_dimensions()
    assert minecraft_to_tile_cell(DEFAULT_MINECRAFT_MIN_X, DEFAULT_MINECRAFT_MIN_Z, bounds) == (0, 0, 0, 0)
    assert minecraft_to_tile_cell(0, 0, bounds) == (34, 72, 180, 61)


def test_default_origin_places_nottingham_at_minecraft_zero_zero():
    assert minecraft_min_for_bng_origin(
        bng_easting=NOTTINGHAM_ORIGIN_BNG_EASTING,
        bng_northing=NOTTINGHAM_ORIGIN_BNG_NORTHING,
    ) == (DEFAULT_MINECRAFT_MIN_X, DEFAULT_MINECRAFT_MIN_Z)
