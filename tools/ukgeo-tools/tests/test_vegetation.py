from ukgeo.vegetation import (
    LCM_TO_VEGETATION,
    VEGETATION_CLASSES,
    cell_blocks_for_metres,
    clean_vegetation_grid,
    generate_biome_region_grid,
    resample_blocks_to_cells,
)
import numpy as np


def test_lcm_classes_map_to_runtime_vegetation_classes():
    assert int(LCM_TO_VEGETATION[1]) == 1
    assert int(LCM_TO_VEGETATION[2]) == 2
    assert int(LCM_TO_VEGETATION[3]) == 3
    assert int(LCM_TO_VEGETATION[8]) == 8
    assert int(LCM_TO_VEGETATION[11]) == 8
    assert int(LCM_TO_VEGETATION[19]) == 8
    assert int(LCM_TO_VEGETATION[14]) == 10
    assert int(LCM_TO_VEGETATION[20]) == 11
    assert int(LCM_TO_VEGETATION[21]) == 11
    assert int(LCM_TO_VEGETATION[15]) == 12
    assert int(LCM_TO_VEGETATION[18]) == 12
    assert int(LCM_TO_VEGETATION[13]) == 0


def test_runtime_vegetation_classes_have_names_and_colors():
    for class_id in range(13):
        meta = VEGETATION_CLASSES[class_id]
        assert meta["name"]
        assert meta["color"].startswith("#")


def test_cell_blocks_for_fifty_metre_cells_on_gb_extent():
    geo = {
        "bng_min_easting": 0.0,
        "bng_min_northing": 0.0,
        "bng_max_easting": 650000.0,
        "bng_max_northing": 1300000.0,
    }
    assert cell_blocks_for_metres(50.0, geo, 25000, 50000) == 2


def test_resample_blocks_to_cells_uses_majority_class():
    blocks = np.array(
        [
            [1, 1, 2, 2],
            [1, 1, 2, 2],
            [3, 3, 3, 3],
            [3, 3, 3, 3],
        ],
        dtype=np.uint8,
    )
    cells = resample_blocks_to_cells(blocks, 2)
    assert cells.tolist() == [[1, 2], [3, 3]]


def test_clean_vegetation_grid_absorbs_single_nonfreshwater_speckle():
    grid = np.full((5, 5), 4, dtype=np.uint8)
    grid[2, 2] = 7
    cleaned = clean_vegetation_grid(grid, smoothing="light")
    assert int(cleaned[2, 2]) == 4


def test_clean_vegetation_grid_preserves_freshwater_and_excludes_it_as_replacement():
    grid = np.array(
        [
            [10, 10, 10, 10, 10],
            [10, 4, 4, 4, 10],
            [10, 4, 7, 4, 10],
            [10, 4, 4, 4, 10],
            [10, 10, 10, 10, 10],
        ],
        dtype=np.uint8,
    )
    cleaned = clean_vegetation_grid(grid, smoothing="light")
    assert int(cleaned[0, 0]) == 10
    assert int(cleaned[4, 4]) == 10
    assert int(cleaned[2, 2]) == 4
    assert int(np.count_nonzero(cleaned == 10)) >= int(np.count_nonzero(grid == 10))


def test_clean_vegetation_grid_removes_tiny_freshwater_fragment():
    grid = np.full((7, 7), 4, dtype=np.uint8)
    grid[3, 3] = 10
    cleaned = clean_vegetation_grid(grid, smoothing="light")
    assert int(cleaned[3, 3]) == 4


def test_clean_vegetation_grid_keeps_unclear_boundaries():
    grid = np.array(
        [
            [4, 4, 7, 7],
            [4, 4, 7, 7],
            [5, 5, 6, 6],
            [5, 5, 6, 6],
        ],
        dtype=np.uint8,
    )
    cleaned = clean_vegetation_grid(grid, smoothing="light")
    assert np.array_equal(cleaned, grid)


def test_biome_regions_absorb_tiny_speckles():
    grid = np.full((12, 12), 9, dtype=np.uint8)
    grid[2, 2] = 2
    grid[8, 8] = 4

    regions = generate_biome_region_grid(grid, region_factor=1, smoothing_passes=1, min_area_cells=4)

    assert int(regions[2, 2]) == 9
    assert int(regions[8, 8]) == 9
    assert np.all(regions == 9)


def test_biome_regions_preserve_large_conifer_patch():
    grid = np.full((16, 16), 4, dtype=np.uint8)
    grid[4:12, 4:12] = 2

    regions = generate_biome_region_grid(grid, region_factor=1, smoothing_passes=1, min_area_cells=8)

    assert int(regions[8, 8]) == 2
    assert int(regions[0, 0]) == 4


def test_biome_regions_preserve_hard_water_classes():
    grid = np.full((8, 8), 5, dtype=np.uint8)
    grid[1:3, 1:3] = 10
    grid[5:7, 5:7] = 0

    regions = generate_biome_region_grid(grid, region_factor=1, smoothing_passes=2, min_area_cells=8)

    assert np.all(regions[grid == 10] == 10)
    assert np.all(regions[grid == 0] == 0)
