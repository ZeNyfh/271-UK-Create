from __future__ import annotations

from pathlib import Path
import gzip
import numpy as np

from .coords import tile_filename


HEIGHT_NODATA = -32768


def write_r16_tile(path: Path, array: np.ndarray) -> None:
    arr = np.asarray(array, dtype="<i2")
    if arr.shape != (512, 512):
        raise ValueError(f"height tile must be 512x512, got {arr.shape}")
    path.parent.mkdir(parents=True, exist_ok=True)
    with gzip.open(path, "wb") as fh:
        fh.write(arr.tobytes(order="C"))


def read_r16_tile(path: Path, tile_size: int = 512) -> np.ndarray:
    with gzip.open(path, "rb") as fh:
        data = fh.read()
    expected = tile_size * tile_size * 2
    if len(data) != expected:
        raise ValueError(f"{path} decompressed to {len(data)} bytes, expected {expected}")
    return np.frombuffer(data, dtype="<i2").reshape((tile_size, tile_size))


def write_u8_tile(path: Path, array: np.ndarray) -> None:
    arr = np.asarray(array, dtype=np.uint8)
    if arr.shape != (512, 512):
        raise ValueError(f"ore tile must be 512x512, got {arr.shape}")
    path.parent.mkdir(parents=True, exist_ok=True)
    with gzip.open(path, "wb") as fh:
        fh.write(arr.tobytes(order="C"))


def read_u8_tile(path: Path, tile_size: int = 512) -> np.ndarray:
    with gzip.open(path, "rb") as fh:
        data = fh.read()
    expected = tile_size * tile_size
    if len(data) != expected:
        raise ValueError(f"{path} decompressed to {len(data)} bytes, expected {expected}")
    return np.frombuffer(data, dtype=np.uint8).reshape((tile_size, tile_size))


def tile_path(root: Path, tile_x: int, tile_z: int, extension: str) -> Path:
    return root / tile_filename(tile_x, tile_z, extension)

