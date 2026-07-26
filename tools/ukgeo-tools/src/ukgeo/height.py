from __future__ import annotations

from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
import io
import math
import os
import zipfile

import numpy as np
from rich.console import Console
from tqdm import tqdm

from .asc import read_header_from_binary
from .manifest import default_manifest, write_manifest
from .tiles import HEIGHT_NODATA, r16_extension, read_r16_tile, resolve_existing_tile, write_r16_tile

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
    axis_scale_x: float = 1.0,
    axis_scale_z: float = 1.0,
    height_resampling: str = "nearest",
    height_smoothing: str = "none",
    height_deterrace: bool = False,
    height_jobs: int = 1,
    debug_geotiff: Path | None = None,
) -> None:
    height_resampling = _normalise_choice(height_resampling, {"nearest", "bilinear"}, "height_resampling")
    height_smoothing = _normalise_choice(height_smoothing, {"none", "light", "medium"}, "height_smoothing")
    padded_width = math.ceil(world_width / tile_size) * tile_size
    padded_depth = math.ceil(world_depth / tile_size) * tile_size
    cells = padded_width * padded_depth
    gib = cells * 2 / (1024**3)
    height_jobs = max(1, int(height_jobs))
    disk_backed = cells > 500_000_000
    source_path = out / ".height-source.i2"
    processed_path = out / ".height-processed.i2"
    if disk_backed:
        console.print(f"[yellow]Using disk-backed height mosaics ({gib:.2f} GiB each, {height_jobs} processing worker(s)).[/yellow]")
        result = np.memmap(source_path, mode="w+", dtype="<i2", shape=(padded_depth, padded_width))
        result[:] = HEIGHT_NODATA
        result.flush()
        _verify_backing_file(source_path, cells * np.dtype("<i2").itemsize, "source mosaic")
    else:
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
        sampled = sample_asc_heights(data, header, xs, ys, height_resampling)
        decimetres = np.rint(sampled * 10.0).clip(-32767, 32767).astype("<i2")
        if header.nodata_value is not None:
            decimetres[sampled == header.nodata_value] = HEIGHT_NODATA
        result[z0c:z1c, x0c:x1c] = decimetres

    if disk_backed:
        result.flush()
        _verify_backing_file(source_path, cells * np.dtype("<i2").itemsize, "completed source mosaic")

    if height_smoothing != "none" or height_deterrace:
        result = process_height_mosaic(
            result, smoothing=height_smoothing, deterrace=height_deterrace,
            output_path=processed_path if disk_backed else None, jobs=height_jobs,
        )
        if disk_backed:
            _verify_backing_file(processed_path, cells * np.dtype("<i2").itemsize, "processed mosaic")

    for tile_z in tqdm(range(padded_depth // tile_size), desc="height tile rows"):
        for tile_x in range(padded_width // tile_size):
            tile = result[
                tile_z * tile_size : (tile_z + 1) * tile_size,
                tile_x * tile_size : (tile_x + 1) * tile_size,
            ]
            _write_verified_height_tile(out / "height" / f"{tile_x:03d}_{tile_z:03d}{r16_extension()}", tile, tile_size)

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
        axis_scale_x=axis_scale_x,
        axis_scale_z=axis_scale_z,
    )
    manifest["height_processing"] = {
        "source": "OS Terrain 50",
        "resampling": height_resampling,
        "smoothing": height_smoothing,
        "deterrace": height_deterrace,
    }
    write_manifest(out / "manifest.json", manifest)
    if debug_geotiff:
        _write_debug_geotiff(debug_geotiff, result, manifest)
    if isinstance(result, np.memmap):
        result.flush()
    for path in (source_path, processed_path):
        path.unlink(missing_ok=True)


def _normalise_choice(value: str, allowed: set[str], name: str) -> str:
    normalised = value.lower().replace("_", "-")
    if normalised not in allowed:
        raise ValueError(f"{name} must be one of {', '.join(sorted(allowed))}, got {value!r}")
    return normalised


def _verify_backing_file(path: Path, expected_bytes: int, label: str) -> None:
    """Force the kernel to report write failures before the next generation stage begins."""
    if not path.is_file() or path.stat().st_size != expected_bytes:
        raise RuntimeError(f"{label} verification failed: {path} has unexpected size")
    with path.open("rb+") as handle:
        handle.flush()
        os.fsync(handle.fileno())
        # Touch beginning, middle and end after sync so disconnected/failed media is detected now.
        for offset in (0, max(0, expected_bytes // 2 - 1), max(0, expected_bytes - 1)):
            if len(os.pread(handle.fileno(), 1, offset)) != 1:
                raise RuntimeError(f"{label} verification failed: unreadable byte at {offset}")


def _write_verified_height_tile(path: Path, tile: np.ndarray, tile_size: int) -> None:
    expected = np.asarray(tile, dtype="<i2")
    write_r16_tile(path, expected)
    resolved = resolve_existing_tile(path)
    if np.all(expected == HEIGHT_NODATA):
        if resolved.exists():
            raise RuntimeError(f"height tile verification failed: empty tile was written at {resolved}")
        return
    with resolved.open("rb+") as handle:
        handle.flush()
        os.fsync(handle.fileno())
    actual = read_r16_tile(path, tile_size)
    if not np.array_equal(actual, expected):
        raise RuntimeError(f"height tile verification failed: read-back mismatch for {resolved}")


def sample_asc_heights(data: np.ndarray, header, xs: np.ndarray, ys: np.ndarray, resampling: str) -> np.ndarray:
    if resampling == "nearest":
        src_cols = np.clip(((xs - header.xllcorner) / header.cellsize).astype(int), 0, header.ncols - 1)
        src_rows = np.clip(((header.yllcorner + header.nrows * header.cellsize - ys) / header.cellsize).astype(int), 0, header.nrows - 1)
        return data[src_rows[:, None], src_cols[None, :]]
    if resampling == "bilinear":
        return _sample_asc_bilinear(data, header, xs, ys)
    raise ValueError(f"unknown height resampling mode: {resampling}")


def _sample_asc_bilinear(data: np.ndarray, header, xs: np.ndarray, ys: np.ndarray) -> np.ndarray:
    top = header.yllcorner + header.nrows * header.cellsize
    src_x = (xs - header.xllcorner) / header.cellsize - 0.5
    src_y = (top - ys) / header.cellsize - 0.5

    x0 = np.floor(src_x).astype(np.int64)
    y0 = np.floor(src_y).astype(np.int64)
    fx = (src_x - x0).astype(np.float32)
    fy = (src_y - y0).astype(np.float32)

    x0c = np.clip(x0, 0, header.ncols - 1)
    x1c = np.clip(x0 + 1, 0, header.ncols - 1)
    y0c = np.clip(y0, 0, header.nrows - 1)
    y1c = np.clip(y0 + 1, 0, header.nrows - 1)

    v00 = data[y0c[:, None], x0c[None, :]].astype(np.float32)
    v10 = data[y0c[:, None], x1c[None, :]].astype(np.float32)
    v01 = data[y1c[:, None], x0c[None, :]].astype(np.float32)
    v11 = data[y1c[:, None], x1c[None, :]].astype(np.float32)

    wx0 = (1.0 - fx)[None, :]
    wx1 = fx[None, :]
    wy0 = (1.0 - fy)[:, None]
    wy1 = fy[:, None]
    w00 = wy0 * wx0
    w10 = wy0 * wx1
    w01 = wy1 * wx0
    w11 = wy1 * wx1

    if header.nodata_value is None:
        return v00 * w00 + v10 * w10 + v01 * w01 + v11 * w11

    nodata = float(header.nodata_value)
    valid00 = v00 != nodata
    valid10 = v10 != nodata
    valid01 = v01 != nodata
    valid11 = v11 != nodata

    numerator = (
        np.where(valid00, v00 * w00, 0.0)
        + np.where(valid10, v10 * w10, 0.0)
        + np.where(valid01, v01 * w01, 0.0)
        + np.where(valid11, v11 * w11, 0.0)
    )
    denominator = (
        np.where(valid00, w00, 0.0)
        + np.where(valid10, w10, 0.0)
        + np.where(valid01, w01, 0.0)
        + np.where(valid11, w11, 0.0)
    )
    nearest_cols = np.clip(((xs - header.xllcorner) / header.cellsize).astype(int), 0, header.ncols - 1)
    nearest_rows = np.clip(((top - ys) / header.cellsize).astype(int), 0, header.nrows - 1)
    nearest = data[nearest_rows[:, None], nearest_cols[None, :]].astype(np.float32)
    return np.where(denominator > 0.0, numerator / np.maximum(denominator, 1.0e-6), nearest)


def process_height_mosaic(data: np.ndarray, *, smoothing: str, deterrace: bool, strip_rows: int = 512, output_path: Path | None = None, jobs: int = 1) -> np.ndarray:
    smoothing = _normalise_choice(smoothing, {"none", "light", "medium"}, "smoothing")
    if smoothing == "none" and not deterrace:
        return data

    source = data if isinstance(data, np.memmap) and data.dtype == np.dtype("<i2") else np.asarray(data, dtype="<i2")
    output = np.memmap(output_path, mode="w+", dtype="<i2", shape=source.shape) if output_path else source.copy()
    height, width = source.shape
    if isinstance(source, np.memmap):
        strip_rows = min(strip_rows, max(8, 1_000_000 // max(1, width)))
    ranges = [(z0, min(height, z0 + strip_rows)) for z0 in range(0, height, strip_rows)]

    def process_range(bounds: tuple[int, int]) -> None:
        z0, z1 = bounds
        output[z0:z1, :] = _process_height_strip(source, z0, z1, smoothing=smoothing, deterrace=deterrace)

    if jobs > 1:
        with ThreadPoolExecutor(max_workers=jobs) as executor:
            for _ in tqdm(executor.map(process_range, ranges), total=len(ranges), desc="height processing rows"):
                pass
    else:
        for bounds in tqdm(ranges, desc="height processing rows"):
            process_range(bounds)
    if isinstance(output, np.memmap): output.flush()
    return output


def _process_height_strip(source: np.ndarray, z0: int, z1: int, *, smoothing: str, deterrace: bool) -> np.ndarray:
    pad_top = max(0, z0 - 1)
    pad_bottom = min(source.shape[0], z1 + 1)
    window = source[pad_top:pad_bottom, :].astype(np.float32)
    center_start = z0 - pad_top
    center_end = center_start + (z1 - z0)
    center = window[center_start:center_end, :]
    valid_center = (center != HEIGHT_NODATA) & (center > 0)

    weighted_total = np.zeros_like(center, dtype=np.float32)
    weight_total = np.zeros_like(center, dtype=np.float32)
    neighbour_count = np.zeros_like(center, dtype=np.uint8)
    local_min = np.full_like(center, np.inf, dtype=np.float32)
    local_max = np.full_like(center, -np.inf, dtype=np.float32)

    for dz in (-1, 0, 1):
        src_z0 = center_start + dz
        src_z1 = center_end + dz
        if src_z0 < 0 or src_z1 > window.shape[0]:
            continue
        for dx in (-1, 0, 1):
            sample = window[src_z0:src_z1, :]
            if dx < 0:
                shifted = np.empty_like(sample)
                shifted[:, 0] = HEIGHT_NODATA
                shifted[:, 1:] = sample[:, :-1]
            elif dx > 0:
                shifted = np.empty_like(sample)
                shifted[:, -1] = HEIGHT_NODATA
                shifted[:, :-1] = sample[:, 1:]
            else:
                shifted = sample
            valid = (shifted != HEIGHT_NODATA) & (shifted > 0)
            weight = 4.0 if dx == 0 and dz == 0 else 2.0 if abs(dx) + abs(dz) == 1 else 1.0
            weighted_total += np.where(valid, shifted * weight, 0.0)
            weight_total += np.where(valid, weight, 0.0)
            neighbour_count += valid.astype(np.uint8)
            local_min = np.where(valid, np.minimum(local_min, shifted), local_min)
            local_max = np.where(valid, np.maximum(local_max, shifted), local_max)

    local_delta = local_max - local_min
    average = weighted_total / np.maximum(weight_total, 1.0)
    processed = center.copy()
    can_process = valid_center & (neighbour_count >= 5) & np.isfinite(local_delta)

    if smoothing != "none":
        if smoothing == "light":
            gentle_amount, moderate_amount, steep_amount = 0.35, 0.22, 0.10
            gentle_max, moderate_max, steep_max = 8.0, 5.0, 2.0
        else:
            gentle_amount, moderate_amount, steep_amount = 0.55, 0.35, 0.18
            gentle_max, moderate_max, steep_max = 12.0, 8.0, 4.0

        amount = np.where(
            local_delta <= 20.0,
            gentle_amount,
            np.where(local_delta <= 80.0, moderate_amount, np.where(local_delta <= 180.0, steep_amount, 0.0)),
        )
        max_change = np.where(
            local_delta <= 20.0,
            gentle_max,
            np.where(local_delta <= 80.0, moderate_max, np.where(local_delta <= 180.0, steep_max, 0.0)),
        )
        delta = np.clip((average - center) * amount, -max_change, max_change)
        processed = np.where(can_process & (amount > 0.0), center + delta, processed)

    if deterrace:
        z_coords = np.arange(z0, z1, dtype=np.float32)[:, None]
        x_coords = np.arange(source.shape[1], dtype=np.float32)[None, :]
        noise = (
            np.sin(x_coords * 0.37 + z_coords * 0.21 + 11.7)
            + 0.5 * np.sin(x_coords * 0.13 - z_coords * 0.19 + 3.1)
        ) / 1.5
        max_jitter = np.where(local_delta <= 20.0, 10.0, np.where(local_delta <= 80.0, 6.0, np.where(local_delta <= 180.0, 2.0, 0.0)))
        processed = np.where(can_process, processed + noise * max_jitter, processed)

    rounded = np.rint(processed).clip(-32767, 32767)
    rounded = np.where(valid_center, rounded, center)
    return rounded.astype("<i2")


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
