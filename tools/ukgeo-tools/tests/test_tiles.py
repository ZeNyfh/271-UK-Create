import numpy as np

from ukgeo.tiles import read_r16_tile, read_u8_tile, write_r16_tile, write_u8_tile


def test_r16_roundtrip(tmp_path):
    arr = np.arange(512 * 512, dtype=np.int32).reshape(512, 512) % 32000
    arr = arr.astype("<i2")
    path = tmp_path / "000_000.r16.gz"
    write_r16_tile(path, arr)
    assert np.array_equal(read_r16_tile(path), arr)


def test_u8_roundtrip(tmp_path):
    arr = (np.arange(512 * 512, dtype=np.uint32).reshape(512, 512) % 256).astype(np.uint8)
    path = tmp_path / "000_000.u8.gz"
    write_u8_tile(path, arr)
    assert np.array_equal(read_u8_tile(path), arr)

