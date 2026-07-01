from __future__ import annotations

from pathlib import Path
import gzip
import os
import numpy as np

from .coords import tile_filename


HEIGHT_NODATA = -32768


def tile_compression() -> str:
    """Return the output tile compression mode for newly generated tiles.

    The runtime mod reads both compressed and uncompressed tiles.  New datasets
    default to uncompressed files because Minecraft performs many small random
    tile reads during chunk generation, where gzip decompression CPU cost tends
    to be more expensive than the extra disk space.
    """
    value = os.environ.get("UKGEO_TILE_COMPRESSION", "none").strip().lower()
    if value in {"", "none", "raw", "uncompressed", "off", "0", "false"}:
        return "none"
    if value in {"gzip", "gz", "compressed", "on", "1", "true"}:
        return "gzip"
    raise ValueError("UKGEO_TILE_COMPRESSION must be 'none' or 'gzip'")


def r16_extension(compression: str | None = None) -> str:
    mode = tile_compression() if compression is None else compression
    return ".r16.gz" if mode == "gzip" else ".r16"


def u8_extension(compression: str | None = None) -> str:
    mode = tile_compression() if compression is None else compression
    return ".u8.gz" if mode == "gzip" else ".u8"


def is_gzip_path(path: Path) -> bool:
    return path.name.lower().endswith(".gz")


def gzip_alternate(path: Path) -> Path:
    name = path.name
    if name.lower().endswith(".gz"):
        return path.with_name(name[:-3])
    return path.with_name(name + ".gz")


def resolve_existing_tile(path: Path) -> Path:
    if path.exists():
        return path
    alt = gzip_alternate(path)
    if alt.exists():
        return alt
    return path


def _read_tile_bytes(path: Path, expected: int) -> bytes:
    resolved = resolve_existing_tile(path)
    if is_gzip_path(resolved):
        with gzip.open(resolved, "rb") as fh:
            data = fh.read()
        size_label = "decompressed"
    else:
        data = resolved.read_bytes()
        size_label = "raw"
    if len(data) != expected:
        raise ValueError(f"{resolved} {size_label} size is {len(data)} bytes, expected {expected}")
    return data


def write_r16_tile(path: Path, array: np.ndarray) -> None:
    arr = np.asarray(array, dtype="<i2")
    if arr.shape != (512, 512):
        raise ValueError(f"height tile must be 512x512, got {arr.shape}")
    path.parent.mkdir(parents=True, exist_ok=True)
    data = arr.tobytes(order="C")
    if is_gzip_path(path):
        with gzip.open(path, "wb", compresslevel=1) as fh:
            fh.write(data)
    else:
        path.write_bytes(data)


def read_r16_tile(path: Path, tile_size: int = 512) -> np.ndarray:
    data = _read_tile_bytes(path, tile_size * tile_size * 2)
    return np.frombuffer(data, dtype="<i2").reshape((tile_size, tile_size))


def write_u8_tile(path: Path, array: np.ndarray, tile_size: int = 512) -> None:
    arr = np.asarray(array, dtype=np.uint8)
    if arr.shape != (tile_size, tile_size):
        raise ValueError(f"uint8 tile must be {tile_size}x{tile_size}, got {arr.shape}")
    path.parent.mkdir(parents=True, exist_ok=True)
    data = arr.tobytes(order="C")
    if is_gzip_path(path):
        with gzip.open(path, "wb", compresslevel=1) as fh:
            fh.write(data)
    else:
        path.write_bytes(data)


def read_u8_tile(path: Path, tile_size: int = 512) -> np.ndarray:
    data = _read_tile_bytes(path, tile_size * tile_size)
    return np.frombuffer(data, dtype=np.uint8).reshape((tile_size, tile_size))


def tile_path(root: Path, tile_x: int, tile_z: int, extension: str) -> Path:
    return root / tile_filename(tile_x, tile_z, extension)
