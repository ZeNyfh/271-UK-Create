from ukgeo.vegetation import LCM_TO_VEGETATION, VEGETATION_CLASSES, cell_blocks_for_metres, resample_blocks_to_cells
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
