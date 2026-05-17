from __future__ import annotations

from pathlib import Path
import io
import math
import zipfile

import numpy as np
from rich.console import Console
from tqdm import tqdm

from .asc import read_header_from_binary
from .manifest import default_manifest, write_manifest
from .tiles import HEIGHT_NODATA, write_r16_tile

console = Console()


def make_height_tiles(
    *,
    os_zip: Path,
    out: Path,
    bng_min_easting: float,
    bng_min_northing: float,
    bng_max_easting: float,
    bng_max_northing: float,
    world_width: int,
    world_depth: int,
    tile_size: int,
    minecraft_min_x: int,
    minecraft_min_z: int,
    sea_level_y: int,
    debug_geotiff: Path | None = None,
) -> None:
    padded_width = math.ceil(world_width / tile_size) * tile_size
    padded_depth = math.ceil(world_depth / tile_size) * tile_size
    cells = padded_width * padded_depth
    gib = cells * 2 / (1024**3)
    console.print(f"[yellow]Height generation currently uses a guarded in-memory mosaic ({gib:.2f} GiB int16 output).[/yellow]")
    if cells > 1_400_000_000:
        raise RuntimeError("Requested output is too large for this first implementation; use a smaller extent or implement windowed VRT generation.")

    result = np.full((padded_depth, padded_width), HEIGHT_NODATA, dtype="<i2")
    x_scale = (bng_max_easting - bng_min_easting) / world_width
    y_scale = (bng_max_northing - bng_min_northing) / world_depth

    for name, payload in tqdm(_iter_asc_entries(os_zip), desc="OS Terrain tiles"):
        with io.BytesIO(payload) as fh:
            header = read_header_from_binary(fh)
            fh.seek(0)
            data = np.loadtxt(fh, skiprows=6 if header.nodata_value is not None else 5, dtype=np.float32)
        if data.shape != (header.nrows, header.ncols):
            console.print(f"[yellow]Skipping {name}: shape {data.shape} does not match header[/yellow]")
            continue
        x0 = int(math.floor((header.xllcorner - bng_min_easting) / x_scale))
        x1 = int(math.ceil((header.xllcorner + header.ncols * header.cellsize - bng_min_easting) / x_scale))
        z0 = int(math.floor((bng_max_northing - (header.yllcorner + header.nrows * header.cellsize)) / y_scale))
        z1 = int(math.ceil((bng_max_northing - header.yllcorner) / y_scale))
        if x1 <= 0 or z1 <= 0 or x0 >= world_width or z0 >= world_depth:
            continue
        x0c, x1c = max(0, x0), min(world_width, x1)
        z0c, z1c = max(0, z0), min(world_depth, z1)
        if x0c >= x1c or z0c >= z1c:
            continue
        xs = bng_min_easting + (np.arange(x0c, x1c) + 0.5) * x_scale
        ys = bng_max_northing - (np.arange(z0c, z1c) + 0.5) * y_scale
        src_cols = np.clip(((xs - header.xllcorner) / header.cellsize).astype(int), 0, header.ncols - 1)
        src_rows = np.clip(((header.yllcorner + header.nrows * header.cellsize - ys) / header.cellsize).astype(int), 0, header.nrows - 1)
        sampled = data[src_rows[:, None], src_cols[None, :]]
        decimetres = np.rint(sampled * 10.0).clip(-32767, 32767).astype("<i2")
        if header.nodata_value is not None:
            decimetres[sampled == header.nodata_value] = HEIGHT_NODATA
        result[z0c:z1c, x0c:x1c] = decimetres

    for tile_z in tqdm(range(padded_depth // tile_size), desc="height tile rows"):
        for tile_x in range(padded_width // tile_size):
            tile = result[
                tile_z * tile_size : (tile_z + 1) * tile_size,
                tile_x * tile_size : (tile_x + 1) * tile_size,
            ]
            write_r16_tile(out / "height" / f"{tile_x:03d}_{tile_z:03d}.r16.gz", tile)

    manifest = default_manifest(
        width=world_width,
        depth=world_depth,
        tile_size=tile_size,
        minecraft_min_x=minecraft_min_x,
        minecraft_min_z=minecraft_min_z,
        sea_level_y=sea_level_y,
        bng_min_easting=bng_min_easting,
        bng_min_northing=bng_min_northing,
        bng_max_easting=bng_max_easting,
        bng_max_northing=bng_max_northing,
    )
    write_manifest(out / "manifest.json", manifest)
    if debug_geotiff:
        _write_debug_geotiff(debug_geotiff, result, manifest)


def _iter_asc_entries(path: Path):
    with zipfile.ZipFile(path) as outer:
        for info in outer.infolist():
            lower = info.filename.lower()
            if lower.endswith(".asc"):
                yield info.filename, outer.read(info)
            elif lower.endswith(".zip"):
                with zipfile.ZipFile(io.BytesIO(outer.read(info))) as nested:
                    for inner in nested.infolist():
                        if inner.filename.lower().endswith(".asc"):
                            yield f"{info.filename}!{inner.filename}", nested.read(inner)


def _write_debug_geotiff(path: Path, data: np.ndarray, manifest: dict) -> None:
    import rasterio
    from rasterio.transform import from_bounds

    geo = manifest["georeferencing"]
    transform = from_bounds(
        geo["bng_min_easting"],
        geo["bng_min_northing"],
        geo["bng_max_easting"],
        geo["bng_max_northing"],
        data.shape[1],
        data.shape[0],
    )
    path.parent.mkdir(parents=True, exist_ok=True)
    with rasterio.open(
        path,
        "w",
        driver="GTiff",
        height=data.shape[0],
        width=data.shape[1],
        count=1,
        dtype="int16",
        crs="EPSG:27700",
        transform=transform,
        nodata=HEIGHT_NODATA,
    ) as dst:
        dst.write(data, 1)
