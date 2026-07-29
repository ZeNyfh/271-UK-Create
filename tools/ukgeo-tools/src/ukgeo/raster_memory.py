from __future__ import annotations

from pathlib import Path
import shutil
import tempfile
from typing import Iterator

import numpy as np

DISK_BACKED_CELL_THRESHOLD = 500_000_000


class U8Raster:
    def __init__(
        self,
        shape: tuple[int, int],
        *,
        tmp_parent: Path,
        label: str,
        resume_path: Path | None = None,
    ):
        self.shape = shape
        self._tmp: tempfile.TemporaryDirectory[str] | None = None
        self._owned_dir: Path | None = None
        cells = int(shape[0]) * int(shape[1])
        if cells > DISK_BACKED_CELL_THRESHOLD:
            if resume_path is not None:
                path = Path(resume_path)
                if not path.is_file():
                    raise FileNotFoundError(f"Resume memmap not found for {label}: {path}")
                expected_bytes = cells
                actual_bytes = path.stat().st_size
                if actual_bytes != expected_bytes:
                    raise ValueError(
                        f"Resume memmap size mismatch for {label}: expected {expected_bytes} bytes, got {actual_bytes}"
                    )
                self._owned_dir = path.parent
                self.array = np.memmap(path, dtype=np.uint8, mode="r+", shape=shape)
            else:
                tmp_parent.mkdir(parents=True, exist_ok=True)
                self._tmp = tempfile.TemporaryDirectory(prefix=f".{label}-", dir=tmp_parent)
                self.array = np.memmap(Path(self._tmp.name) / f"{label}.u1", dtype=np.uint8, mode="w+", shape=shape)
                self.array[:] = 0
                self.flush()
        else:
            if resume_path is not None:
                raise ValueError(f"Resume memmap is only supported for disk-backed rasters ({label})")
            self.array = np.zeros(shape, dtype=np.uint8)

    def flush(self) -> None:
        if isinstance(self.array, np.memmap):
            self.array.flush()

    def cleanup(self) -> None:
        self.flush()
        if self._tmp is not None:
            self._tmp.cleanup()
            self._tmp = None
        elif self._owned_dir is not None:
            shutil.rmtree(self._owned_dir, ignore_errors=True)
            self._owned_dir = None

    def __enter__(self) -> np.ndarray:
        return self.array

    def __exit__(self, exc_type, exc, tb) -> None:
        # Keep resumed orphan memmaps on failure so another resume can retry.
        if exc_type is not None and self._owned_dir is not None:
            self.flush()
            self._owned_dir = None
            return
        self.cleanup()


def find_memmap_path(tmp_parent: Path, label: str) -> Path:
    matches = sorted(tmp_parent.glob(f".{label}-*/{label}.u1"))
    if not matches:
        raise FileNotFoundError(f"No orphaned memmap found for label {label!r} under {tmp_parent}")
    if len(matches) > 1:
        listed = ", ".join(str(path) for path in matches)
        raise FileNotFoundError(f"Multiple orphaned memmaps for label {label!r}; refuse to guess: {listed}")
    return matches[0]


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
