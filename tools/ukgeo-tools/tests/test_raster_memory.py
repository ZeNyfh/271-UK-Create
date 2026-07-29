from __future__ import annotations

from pathlib import Path

import numpy as np
import pytest

from ukgeo.raster_memory import U8Raster, find_memmap_path


def test_find_memmap_path_requires_unique_match(tmp_path: Path):
    label = "rivers"
    only = tmp_path / f".{label}-abc"
    only.mkdir()
    path = only / f"{label}.u1"
    path.write_bytes(b"\x00")
    assert find_memmap_path(tmp_path, label) == path

    second = tmp_path / f".{label}-def"
    second.mkdir()
    (second / f"{label}.u1").write_bytes(b"\x00")
    with pytest.raises(FileNotFoundError, match="Multiple orphaned memmaps"):
        find_memmap_path(tmp_path, label)


def test_u8_raster_resume_reopens_without_zeroing(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    monkeypatch.setattr("ukgeo.raster_memory.DISK_BACKED_CELL_THRESHOLD", 3)
    shape = (2, 2)
    orphan_dir = tmp_path / ".rivers-resume"
    orphan_dir.mkdir()
    reopen = orphan_dir / "rivers.u1"
    reopen.write_bytes(bytes([0, 1, 2, 3]))

    with U8Raster(shape, tmp_parent=tmp_path, label="rivers", resume_path=reopen) as arr:
        assert list(arr.reshape(-1)) == [0, 1, 2, 3]
        arr[0, 0] = 9
        arr.flush()

    assert not orphan_dir.exists()


def test_u8_raster_resume_preserves_files_on_error(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    monkeypatch.setattr("ukgeo.raster_memory.DISK_BACKED_CELL_THRESHOLD", 3)
    shape = (2, 2)
    orphan_dir = tmp_path / ".rivers-resume"
    orphan_dir.mkdir()
    reopen = orphan_dir / "rivers.u1"
    reopen.write_bytes(bytes([4, 5, 6, 7]))

    with pytest.raises(RuntimeError, match="boom"):
        with U8Raster(shape, tmp_parent=tmp_path, label="rivers", resume_path=reopen) as arr:
            assert int(arr[0, 0]) == 4
            raise RuntimeError("boom")

    assert orphan_dir.exists()
    assert reopen.read_bytes() == bytes([4, 5, 6, 7])
