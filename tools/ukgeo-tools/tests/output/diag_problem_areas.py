#!/usr/bin/env python3
"""Diagnostic previews for UKGeo river/lake problem sites."""
from __future__ import annotations

import json
import math
from pathlib import Path

import numpy as np

try:
    from PIL import Image
except ImportError:
    Image = None

ROOT = Path(__file__).resolve().parents[2] / "uk_world_data_gb"
OUT = Path(__file__).resolve().parent

SITES = {
    "uneven_banks": {"x": -13757, "z": -34570, "radius": 48},
    "mountain_river": {"x": -13957, "z": -34342, "radius": 48},
    "spawn_water": {"x": -255, "z": 380, "radius": 16},
}


def load_manifest(root: Path) -> dict:
    return json.loads((root / "manifest.json").read_text())


def minecraft_to_data(manifest: dict, x: int, z: int) -> tuple[int, int]:
    world = manifest["world"]
    min_x = int(world["minecraft_min_x"])
    min_z = int(world["minecraft_min_z"])
    return x - min_x, z - min_z


def tile_size(manifest: dict) -> int:
    return int(manifest.get("tile_size", 512))


def read_tile(path: Path, dtype: str, shape: tuple[int, int]) -> np.ndarray:
    if not path.exists():
        return np.zeros(shape, dtype=np.dtype(dtype))
    arr = np.fromfile(path, dtype=np.dtype(dtype))
    if arr.size != shape[0] * shape[1]:
        return np.zeros(shape, dtype=np.dtype(dtype))
    return arr.reshape(shape)


def sample_layer(root, rel, extension, dtype, data_x, data_z, radius, ts):
    size = radius * 2 + 1
    out = np.zeros((size, size), dtype=np.dtype(dtype))
    for dz in range(-radius, radius + 1):
        for dx in range(-radius, radius + 1):
            gx = data_x + dx
            gz = data_z + dz
            tx, tz = gx // ts, gz // ts
            lx, lz = gx - tx * ts, gz - tz * ts
            path = root / rel / f"{tx:03d}_{tz:03d}{extension}"
            tile = read_tile(path, dtype, (ts, ts))
            out[dz + radius, dx + radius] = tile[lz, lx]
    return out


def colourise_height(arr):
    finite = np.isfinite(arr.astype(np.float64))
    if not finite.any():
        return np.zeros((*arr.shape, 3), dtype=np.uint8)
    lo, hi = float(np.min(arr[finite])), float(np.max(arr[finite]))
    span = max(1.0, hi - lo)
    norm = ((arr.astype(np.float64) - lo) / span).clip(0, 1)
    rgb = np.zeros((*arr.shape, 3), dtype=np.uint8)
    rgb[..., 0] = (40 + 180 * norm).astype(np.uint8)
    rgb[..., 1] = (60 + 140 * (1.0 - norm)).astype(np.uint8)
    rgb[..., 2] = (90 + 100 * norm).astype(np.uint8)
    return rgb


def colourise_water_y(water, dem_y):
    rgb = colourise_height(dem_y)
    nodata = water <= -32000
    float_mask = (~nodata) & (water.astype(np.int32) > dem_y.astype(np.int32))
    rgb[float_mask] = (255, 40, 40)
    wet = (~nodata) & ~float_mask
    rgb[wet] = (40, 120, 220)
    return rgb


def save_png(path, rgb):
    if Image is None:
        return
    Image.fromarray(rgb, mode="RGB").resize(
        (rgb.shape[1] * 4, rgb.shape[0] * 4), resample=Image.NEAREST
    ).save(path)


def cross_section(dem_y, water_y, axis="x"):
    mid = dem_y.shape[0] // 2
    dem = dem_y[mid, :] if axis == "x" else dem_y[:, mid]
    water = water_y[mid, :] if axis == "x" else water_y[:, mid]
    width = dem.shape[0]
    wet = water > -32000
    y_min = int(min(np.min(dem), np.min(water[wet]) if wet.any() else np.min(dem)) - 4)
    y_max = int(max(np.max(dem), np.max(water[wet]) if wet.any() else np.max(dem)) + 4)
    height = y_max - y_min + 1
    img = np.zeros((height, width, 3), dtype=np.uint8)
    img[:] = (30, 30, 30)
    for i in range(width):
        d = int(dem[i]) - y_min
        img[0:d, i] = (90, 70, 50)
        w = int(water[i])
        if w > -32000:
            wy = w - y_min
            bed = max(0, min(d, wy - 1))
            img[bed:wy, i] = (50, 130, 230)
            if w > int(dem[i]):
                img[d:wy, i] = (255, 60, 60)
    return np.flipud(img)


def approx_surface_y(height_dm, sea=64):
    metres = height_dm.astype(np.float64) * 0.1
    out = np.empty_like(metres, dtype=np.int32)
    for idx, m in np.ndenumerate(metres):
        if not math.isfinite(float(m)):
            out[idx] = sea
            continue
        lowland_weight = 1.0 - min(1.0, max(0.0, float(m) / 120.0)) if m > 0 else 1.0
        highland_weight = 0.0
        if m > 120.0:
            highland_weight = min(1.0, max(0.0, (float(m) - 120.0) / 630.0))
        scale = 0.27 + 0.045 * lowland_weight
        scale = scale + (0.3 - scale) * highland_weight
        out[idx] = int(sea + int(round(float(m) * scale)))
    return out


def diagnose_site(name, x, z, radius, manifest):
    data_x, data_z = minecraft_to_data(manifest, x, z)
    ts = tile_size(manifest)
    rivers = manifest["rivers"]
    height_rel = manifest["height"]["path"]
    height = sample_layer(ROOT, height_rel, ".r16", "<u2", data_x, data_z, radius, ts)
    dem_y = approx_surface_y(height.astype(np.float64))
    water = sample_layer(ROOT, rivers["water_y_path"], rivers.get("water_y_extension", ".r16"), "<i2", data_x, data_z, radius, ts)
    reach = sample_layer(ROOT, rivers["reach_id_path"], rivers.get("reach_id_extension", ".u32"), "<u4", data_x, data_z, radius, ts)
    station = sample_layer(ROOT, rivers["station_id_path"], rivers.get("station_id_extension", ".u16"), "<u2", data_x, data_z, radius, ts)
    mask = sample_layer(ROOT, rivers["path"], ".u8", "u1", data_x, data_z, radius, ts)
    top = colourise_water_y(water, dem_y)
    top[radius, radius] = (255, 255, 0)
    save_png(OUT / f"diag_{name}_topdown.png", top)
    save_png(OUT / f"diag_{name}_xsection_x.png", cross_section(dem_y, water, "x"))
    save_png(OUT / f"diag_{name}_xsection_z.png", cross_section(dem_y, water, "z"))
    cx = cy = radius
    return "\n".join([
        f"site={name} minecraft=({x},{z}) data=({data_x},{data_z})",
        f"dem_y≈{int(dem_y[cy, cx])} water_y={int(water[cy, cx])} reach={int(reach[cy, cx])} station={int(station[cy, cx])} mask={int(mask[cy, cx])}",
        f"float_above_dem={int(water[cy, cx]) > int(dem_y[cy, cx])}",
        f"water max={int(np.max(water))} chunk=({x >> 4},{z >> 4}) local=({x & 15},{z & 15})",
    ])


def main():
    manifest = load_manifest(ROOT)
    reports = [diagnose_site(n, s["x"], s["z"], s["radius"], manifest) for n, s in SITES.items()]
    text = "\n\n".join(reports) + "\n"
    (OUT / "diag_problem_areas.txt").write_text(text)
    print(text)


if __name__ == "__main__":
    main()
