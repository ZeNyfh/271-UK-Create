from __future__ import annotations

from dataclasses import dataclass
from typing import BinaryIO, Iterable
import io
import zipfile


@dataclass(frozen=True)
class AscHeader:
    ncols: int
    nrows: int
    xllcorner: float
    yllcorner: float
    cellsize: float
    nodata_value: float | None = None


def parse_asc_header(lines: Iterable[str]) -> AscHeader:
    values: dict[str, str] = {}
    for raw in lines:
        parts = raw.strip().split()
        if len(parts) < 2:
            continue
        key = parts[0].lower()
        values[key] = parts[1]
        if len(values) >= 6 and {"ncols", "nrows", "xllcorner", "yllcorner", "cellsize"}.issubset(values):
            break
    missing = {"ncols", "nrows", "xllcorner", "yllcorner", "cellsize"} - set(values)
    if missing:
        raise ValueError(f"ASC header missing fields: {', '.join(sorted(missing))}")
    return AscHeader(
        ncols=int(values["ncols"]),
        nrows=int(values["nrows"]),
        xllcorner=float(values["xllcorner"]),
        yllcorner=float(values["yllcorner"]),
        cellsize=float(values["cellsize"]),
        nodata_value=float(values["nodata_value"]) if "nodata_value" in values else None,
    )


def read_header_from_binary(stream: BinaryIO) -> AscHeader:
    lines: list[str] = []
    for _ in range(6):
        line = stream.readline()
        if not line:
            break
        lines.append(line.decode("utf-8", errors="replace"))
    return parse_asc_header(lines)


def iter_nested_asc_headers(outer_zip_path: str):
    with zipfile.ZipFile(outer_zip_path) as outer:
        for outer_info in outer.infolist():
            lower = outer_info.filename.lower()
            if lower.endswith(".asc"):
                with outer.open(outer_info) as asc_file:
                    yield outer_info.filename, read_header_from_binary(asc_file)
            elif lower.endswith(".zip"):
                data = outer.read(outer_info)
                with zipfile.ZipFile(io.BytesIO(data)) as nested:
                    for inner_info in nested.infolist():
                        if inner_info.filename.lower().endswith(".asc"):
                            with nested.open(inner_info) as asc_file:
                                yield f"{outer_info.filename}!{inner_info.filename}", read_header_from_binary(asc_file)
