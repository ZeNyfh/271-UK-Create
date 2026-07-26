from __future__ import annotations

from pathlib import Path
import tempfile
from typing import Iterator

import numpy as np

DISK_BACKED_CELL_THRESHOLD = 500_000_000


class U8Raster:
    def __init__(self, shape: tuple[int, int], *, tmp_parent: Path, label: str):
        self.shape = shape
        self._tmp: tempfile.TemporaryDirectory[str] | None = None
        cells = int(shape[0]) * int(shape[1])
        if cells > DISK_BACKED_CELL_THRESHOLD:
            tmp_parent.mkdir(parents=True, exist_ok=True)
            self._tmp = tempfile.TemporaryDirectory(prefix=f".{label}-", dir=tmp_parent)
            self.array = np.memmap(Path(self._tmp.name) / f"{label}.u1", dtype=np.uint8, mode="w+", shape=shape)
            self.array[:] = 0
            self.flush()
        else:
            self.array = np.zeros(shape, dtype=np.uint8)

    def flush(self) -> None:
        if isinstance(self.array, np.memmap):
            self.array.flush()

    def cleanup(self) -> None:
        self.flush()
        if self._tmp is not None:
            self._tmp.cleanup()
            self._tmp = None

    def __enter__(self) -> np.ndarray:
        return self.array

    def __exit__(self, exc_type, exc, tb) -> None:
        self.cleanup()


def row_windows(height: int, rows: int = 1024) -> Iterator[slice]:
    for start in range(0, height, rows):
        yield slice(start, min(height, start + rows))


def maximum_in_place(target: np.ndarray, source: np.ndarray, *, rows: int = 1024) -> None:
    for window in row_windows(target.shape[0], rows):
        np.maximum(target[window], source[window], out=target[window])
    if isinstance(target, np.memmap):
        target.flush()


def replace_nonzero_in_place(target: np.ndarray, source: np.ndarray, *, rows: int = 1024) -> None:
    for window in row_windows(target.shape[0], rows):
        target_rows = target[window]
        source_rows = source[window]
        mask = source_rows > 0
        target_rows[mask] = source_rows[mask]
    if isinstance(target, np.memmap):
        target.flush()
