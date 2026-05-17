from __future__ import annotations

from pathlib import Path
import csv
from dataclasses import dataclass
import gzip
import math
import zipfile

import numpy as np
from affine import Affine
from pyproj import Transformer
from rasterio.enums import Resampling
from rasterio.transform import from_origin
from rasterio.warp import reproject
from rich.console import Console
from tqdm import tqdm

from .manifest import read_manifest
from .tiles import HEIGHT_NODATA, read_r16_tile, write_r16_tile

console = Console()


@dataclass(frozen=True)
class OsniScan:
    count: int
    min_x: float
    min_y: float
    max_x: float
    max_y: float


def add_osni_height_tiles(
    *,
    osni_dtm: Path,
    manifest_path: Path,
    out: Path,
    source_crs: str = "EPSG:29902",
    source_cell_size: float = 50.0,
    resampling: str = "bilinear",
) -> None:
    manifest = read_manifest(manifest_path)
    geo = manifest["georeferencing"]
    world = manifest["world"]
    tile_size = int(manifest["tile_size"])
    tiles_x = int(world["padded_width"]) // tile_size
    tiles_z = int(world["padded_depth"]) // tile_size
    height_root = out / manifest["height"]["path"]
    method = _resampling(resampling)

    scan = _scan_osni_csv(osni_dtm)
    cols = int(round((scan.max_x - scan.min_x) / source_cell_size)) + 1
    rows = int(round((scan.max_y - scan.min_y) / source_cell_size)) + 1
    console.print(
        f"Loading OSNI DTM grid: {scan.count:,} points, {cols} x {rows} cells @ {source_cell_size:g} m "
        f"({cols * rows * 2 / (1024 ** 2):.1f} MiB int16)."
    )
    source = np.full((rows, cols), HEIGHT_NODATA, dtype=np.int16)
    with _open_csv(osni_dtm) as row_file:
        reader = csv.DictReader(row_file)
        for row in tqdm(reader, total=scan.count, desc="loading OSNI DTM"):
            src_x = float(row["X"])
            src_y = float(row["Y"])
            col = int(round((src_x - scan.min_x) / source_cell_size))
            src_row = int(round((scan.max_y - src_y) / source_cell_size))
            if 0 <= src_row < rows and 0 <= col < cols:
                source[src_row, col] = int(round(float(row["Z"]) * 10.0))

    src_transform = from_origin(scan.min_x - source_cell_size / 2.0, scan.max_y + source_cell_size / 2.0, source_cell_size, source_cell_size)
    transformer = Transformer.from_crs(source_crs, "EPSG:27700", always_xy=True)
    x_scale = (float(geo["bng_max_easting"]) - float(geo["bng_min_easting"])) / int(world["width"])
    z_scale = (float(geo["bng_max_northing"]) - float(geo["bng_min_northing"])) / int(world["depth"])
    tile_min_x, tile_max_x, tile_min_z, tile_max_z = _covered_tiles(scan, transformer, geo, world, tile_size, tiles_x, tiles_z)

    written_tiles = 0
    written_cells = 0
    for tile_z in tqdm(range(tile_min_z, tile_max_z + 1), desc="resampling NI height tile rows"):
        for tile_x in range(tile_min_x, tile_max_x + 1):
            dst = np.full((tile_size, tile_size), HEIGHT_NODATA, dtype=np.int16)
            dst_transform = Affine(
                x_scale,
                0.0,
                float(geo["bng_min_easting"]) + tile_x * tile_size * x_scale,
                0.0,
                -z_scale,
                float(geo["bng_max_northing"]) - tile_z * tile_size * z_scale,
            )
            reproject(
                source=source,
                destination=dst,
                src_transform=src_transform,
                src_crs=source_crs,
                src_nodata=HEIGHT_NODATA,
                dst_transform=dst_transform,
                dst_crs="EPSG:27700",
                dst_nodata=HEIGHT_NODATA,
                resampling=method,
                num_threads=2,
            )
            valid = dst != HEIGHT_NODATA
            if not valid.any():
                continue
            path = height_root / f"{tile_x:03d}_{tile_z:03d}.r16.gz"
            existing = read_r16_tile(path, tile_size) if path.exists() else np.full((tile_size, tile_size), HEIGHT_NODATA, dtype="<i2")
            existing = existing.copy()
            existing[valid] = dst[valid]
            write_r16_tile(path, existing)
            written_tiles += 1
            written_cells += int(valid.sum())
    console.print(f"Resampled OSNI height into {written_cells:,} cells across {written_tiles} height tiles.")


def _scan_osni_csv(path: Path) -> OsniScan:
    count = 0
    min_x = math.inf
    min_y = math.inf
    max_x = -math.inf
    max_y = -math.inf
    with _open_csv(path) as rows:
        reader = csv.DictReader(rows)
        for row in tqdm(reader, desc="scanning OSNI DTM"):
            x = float(row["X"])
            y = float(row["Y"])
            count += 1
            min_x = min(min_x, x)
            min_y = min(min_y, y)
            max_x = max(max_x, x)
            max_y = max(max_y, y)
    if count == 0:
        raise ValueError(f"No OSNI DTM rows found in {path}")
    return OsniScan(count=count, min_x=min_x, min_y=min_y, max_x=max_x, max_y=max_y)


def _covered_tiles(
    scan: OsniScan,
    transformer: Transformer,
    geo: dict,
    world: dict,
    tile_size: int,
    tiles_x: int,
    tiles_z: int,
) -> tuple[int, int, int, int]:
    corners = [
        transformer.transform(scan.min_x, scan.min_y),
        transformer.transform(scan.min_x, scan.max_y),
        transformer.transform(scan.max_x, scan.min_y),
        transformer.transform(scan.max_x, scan.max_y),
    ]
    eastings = [point[0] for point in corners]
    northings = [point[1] for point in corners]
    x_scale = (float(geo["bng_max_easting"]) - float(geo["bng_min_easting"])) / int(world["width"])
    z_scale = (float(geo["bng_max_northing"]) - float(geo["bng_min_northing"])) / int(world["depth"])
    min_data_x = math.floor((min(eastings) - float(geo["bng_min_easting"])) / x_scale)
    max_data_x = math.ceil((max(eastings) - float(geo["bng_min_easting"])) / x_scale)
    min_data_z = math.floor((float(geo["bng_max_northing"]) - max(northings)) / z_scale)
    max_data_z = math.ceil((float(geo["bng_max_northing"]) - min(northings)) / z_scale)
    tile_min_x = max(0, min_data_x // tile_size - 1)
    tile_max_x = min(tiles_x - 1, max_data_x // tile_size + 1)
    tile_min_z = max(0, min_data_z // tile_size - 1)
    tile_max_z = min(tiles_z - 1, max_data_z // tile_size + 1)
    if tile_min_x > tile_max_x or tile_min_z > tile_max_z:
        raise ValueError("OSNI DTM does not overlap the manifest BNG extent")
    return tile_min_x, tile_max_x, tile_min_z, tile_max_z


def _resampling(value: str) -> Resampling:
    normalised = value.strip().lower()
    if normalised == "nearest":
        return Resampling.nearest
    if normalised == "bilinear":
        return Resampling.bilinear
    raise ValueError("--resampling must be nearest or bilinear")


def _open_csv(path: Path):
    if path.suffix.lower() == ".zip":
        archive = zipfile.ZipFile(path)
        names = [name for name in archive.namelist() if name.lower().endswith(".csv")]
        if not names:
            archive.close()
            raise ValueError(f"No CSV file found in {path}")
        raw = archive.open(names[0])
        return _ClosingText(raw, archive)
    raw = path.open("rb")
    if path.suffix.lower() == ".gz":
        raw = gzip.open(raw)
    return _ClosingText(raw, None)


class _ClosingText:
    def __init__(self, raw, archive):
        import io

        self.raw = raw
        self.archive = archive
        self.text = io.TextIOWrapper(raw, encoding="utf-8")

    def __enter__(self):
        return self.text

    def __exit__(self, exc_type, exc, tb):
        self.text.close()
        if self.archive is not None:
            self.archive.close()
