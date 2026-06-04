from types import SimpleNamespace

import numpy as np

from ukgeo.height import process_height_mosaic, sample_asc_heights
from ukgeo.tiles import HEIGHT_NODATA


def _header(*, nodata=None):
    return SimpleNamespace(
        ncols=2,
        nrows=2,
        xllcorner=0.0,
        yllcorner=0.0,
        cellsize=10.0,
        nodata_value=nodata,
    )


def test_bilinear_samples_cell_centres():
    data = np.array([[10.0, 20.0], [30.0, 40.0]], dtype=np.float32)
    xs = np.array([5.0, 15.0], dtype=np.float32)
    ys = np.array([15.0, 5.0], dtype=np.float32)

    sampled = sample_asc_heights(data, _header(), xs, ys, "bilinear")

    assert np.array_equal(sampled, data)


def test_bilinear_interpolates_between_cells():
    data = np.array([[0.0, 10.0], [20.0, 30.0]], dtype=np.float32)
    xs = np.array([10.0], dtype=np.float32)
    ys = np.array([10.0], dtype=np.float32)

    sampled = sample_asc_heights(data, _header(), xs, ys, "bilinear")

    assert sampled.shape == (1, 1)
    assert sampled[0, 0] == 15.0


def test_bilinear_ignores_nodata_neighbours():
    nodata = -9999.0
    data = np.array([[nodata, 10.0], [20.0, 30.0]], dtype=np.float32)
    xs = np.array([10.0], dtype=np.float32)
    ys = np.array([10.0], dtype=np.float32)

    sampled = sample_asc_heights(data, _header(nodata=nodata), xs, ys, "bilinear")

    assert sampled[0, 0] == 20.0


def test_height_processing_preserves_nodata_and_ocean():
    data = np.array(
        [
            [HEIGHT_NODATA, 0, 0, HEIGHT_NODATA],
            [100, 100, 120, 120],
            [100, 110, 120, 130],
            [HEIGHT_NODATA, 0, 0, HEIGHT_NODATA],
        ],
        dtype="<i2",
    )

    processed = process_height_mosaic(data, smoothing="light", deterrace=True, strip_rows=2)

    assert processed.shape == data.shape
    assert processed[0, 0] == HEIGHT_NODATA
    assert processed[0, 1] == 0
    assert processed[3, 2] == 0
