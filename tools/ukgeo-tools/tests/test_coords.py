from ukgeo.coords import WorldBounds, minecraft_to_tile_cell, tile_filename


def test_tile_filename():
    assert tile_filename(1, 2, ".r16.gz") == "001_002.r16.gz"


def test_minecraft_to_tile_cell():
    bounds = WorldBounds.from_dimensions()
    assert minecraft_to_tile_cell(-12500, -25000, bounds) == (0, 0, 0, 0)
    assert minecraft_to_tile_cell(0, 0, bounds) == (24, 48, 212, 424)

