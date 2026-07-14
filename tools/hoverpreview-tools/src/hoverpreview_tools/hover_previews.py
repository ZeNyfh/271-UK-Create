from __future__ import annotations

from pathlib import Path
import gc
import json
import math
import os
import shutil
import time
from concurrent.futures import ThreadPoolExecutor
from collections.abc import Callable
from typing import Any

import numpy as np
from PIL import Image

from ukgeo.manifest import read_manifest
from ukgeo.preview import ORE_COLORS, _height_image as _cpu_height_image, _hex_color, _read_height_preview, _read_u8_preview, read_cell_u8_preview, read_vegetation_preview
from ukgeo.tiles import HEIGHT_NODATA


HOVER_PREVIEW_FORMAT = "ukgeo-hoverpreviews-v1"
HOVER_PREVIEW_INDEX = "hover_manifest.json"
DEFAULT_TILE_SIZE = 256
VISUAL_TILE_SIZE = DEFAULT_TILE_SIZE
SUPPORTED_VISUAL_FORMATS = {"png", "webp"}
PREVIEW_RIVER_WIDTH_SCALE = 0.18
PREVIEW_RIVER_MIN_RADIUS = 1
PREVIEW_RIVER_MAX_RADIUS = 6
PREVIEW_RIVER_COLOR = (65, 145, 230)
_CUPY_MODULE: Any | None | bool = None


def hover_preview_scale(manifest: dict[str, Any], max_size: int) -> tuple[int, int, int]:
    tile_size = int(manifest["tile_size"])
    tiles_x = math.ceil(int(manifest["world"]["padded_width"]) / tile_size)
    tiles_z = math.ceil(int(manifest["world"]["padded_depth"]) / tile_size)
    full_width = tiles_x * tile_size
    full_depth = tiles_z * tile_size
    scale = 1 if max_size <= 0 else max(1, math.ceil(max(full_width, full_depth) / max_size))
    return scale, tiles_x, tiles_z


def hover_preview_steps(root: Path, manifest: dict[str, Any]) -> list[str]:
    steps = ["height"]
    if "surface_geology" in manifest and (root / manifest["surface_geology"]["path"]).exists():
        steps.append("surface")
    if "vegetation" in manifest and (root / manifest["vegetation"]["path"]).exists():
        steps.append("vegetation")
    if "biome_regions" in manifest and (root / manifest["biome_regions"]["path"]).exists():
        steps.append("biome_regions")
    if "rivers" in manifest and (root / manifest["rivers"]["path"]).exists():
        steps.append("rivers")
    for ore, layer in manifest.get("ore_layers", {}).items():
        if ore == "tin":
            continue
        if (root / layer["path"]).exists():
            steps.append(f"ore:{ore}")
    steps.append("manifest")
    return steps


def export_hover_previews(
    root: Path,
    out: Path,
    max_size: int = 4096,
    style: str = "auto",
    clean: bool = False,
    tile_size: int = DEFAULT_TILE_SIZE,
    workers: int | None = None,
    visual_format: str = "png",
    force: bool = False,
    clean_stale: bool = False,
    profile: bool = False,
    progress: Callable[[str], None] | None = None,
) -> Path:
    manifest = read_manifest(root / "manifest.json")
    source_tile_size = int(manifest["tile_size"])
    preview_tile_size = _validate_tile_size(tile_size)
    encoder_workers = _resolve_workers(workers)
    visual_format = visual_format.lower().strip()
    if visual_format not in SUPPORTED_VISUAL_FORMATS:
        raise ValueError(f"Unsupported visual format {visual_format!r}; expected one of {sorted(SUPPORTED_VISUAL_FORMATS)}")
    scale, tiles_x, tiles_z = hover_preview_scale(manifest, max_size)
    timings: list[tuple[str, float]] = []

    def report(step: str) -> None:
        if progress is not None:
            progress(step)

    def timed(step: str) -> Callable[[], None]:
        start = time.perf_counter()

        def done() -> None:
            if profile:
                timings.append((step, time.perf_counter() - start))

        return done

    if clean and out.exists():
        shutil.rmtree(out)
    (out / "layers").mkdir(parents=True, exist_ok=True)
    (out / "samples").mkdir(parents=True, exist_ok=True)
    (out / "mips").mkdir(parents=True, exist_ok=True)

    report("height")
    done = timed("height")
    height_values = _read_height_preview(root, manifest, tiles_x, tiles_z, source_tile_size, scale)
    base_size = (height_values.shape[1], height_values.shape[0])
    height_mips = _save_visual_layer(
        out,
        _height_image(height_values, style).convert("RGB"),
        _visual_layer_path("layers/height", visual_format),
        tile_size=preview_tile_size,
        visual_format=visual_format,
        workers=encoder_workers,
        force=force,
        resampling=Image.Resampling.BILINEAR,
    )
    _height_sample_image(height_values).save(out / "samples" / "height_u16.png")
    height_browser_sample = _height_browser_sample_image(height_values)
    height_browser_sample.save(out / "samples" / "height_rgb.png")
    height_sample_tiles = _save_sample_tiles(
        out,
        height_browser_sample,
        "samples/height_rgb.png",
        tile_size=preview_tile_size,
        encoding="signed-decimetres-rgb-le-offset-32768",
        workers=encoder_workers,
        force=force,
    )
    del height_values
    gc.collect()
    done()

    layers: list[dict[str, Any]] = [
        {
            "name": "height",
            "kind": "base",
            "file": height_mips[0]["file"],
            "mips": height_mips,
            "sample_file": "samples/height_u16.png",
            "browser_sample_file": "samples/height_rgb.png",
            "browser_sample_encoding": "signed-decimetres-rgb-le-offset-32768",
            "sample_tiles": height_sample_tiles,
        },
    ]

    if "surface_geology" in manifest and (root / manifest["surface_geology"]["path"]).exists():
        report("surface")
        done = timed("surface")
        values = _read_u8_preview(root, manifest["surface_geology"]["path"], tiles_x, tiles_z, source_tile_size, scale, missing_ok=False)
        visual = _fit_image(_categorical_overlay_image(values, manifest["surface_geology"].get("classes", {}), alpha=166, transparent_zero=True), base_size)
        sample = _fit_image(Image.fromarray(values, mode="L"), base_size)
        mips = _save_visual_layer(
            out,
            visual,
            _visual_layer_path("layers/surface", visual_format),
            tile_size=preview_tile_size,
            visual_format=visual_format,
            workers=encoder_workers,
            force=force,
            resampling=Image.Resampling.NEAREST,
        )
        sample.save(out / "samples" / "surface_u8.png")
        layers.append({
            "name": "surface",
            "kind": "overlay",
            "file": mips[0]["file"],
            "mips": mips,
            "sample_file": "samples/surface_u8.png",
            "sample_tiles": _save_sample_tiles(out, sample, "samples/surface_u8.png", tile_size=preview_tile_size, encoding="u8", workers=encoder_workers, force=force),
        })
        del values, visual, sample
        gc.collect()
        done()

    if "vegetation" in manifest and (root / manifest["vegetation"]["path"]).exists():
        report("vegetation")
        done = timed("vegetation")
        values = read_vegetation_preview(root, manifest, scale, missing_ok=False)
        visual = _fit_image(_categorical_overlay_image(values, manifest["vegetation"].get("classes", {}), alpha=176, transparent_zero=True), base_size)
        sample = _fit_image(Image.fromarray(values, mode="L"), base_size)
        mips = _save_visual_layer(
            out,
            visual,
            _visual_layer_path("layers/vegetation", visual_format),
            tile_size=preview_tile_size,
            visual_format=visual_format,
            workers=encoder_workers,
            force=force,
            resampling=Image.Resampling.NEAREST,
        )
        sample.save(out / "samples" / "vegetation_u8.png")
        layers.append({
            "name": "vegetation",
            "kind": "overlay",
            "file": mips[0]["file"],
            "mips": mips,
            "sample_file": "samples/vegetation_u8.png",
            "sample_tiles": _save_sample_tiles(out, sample, "samples/vegetation_u8.png", tile_size=preview_tile_size, encoding="u8", workers=encoder_workers, force=force),
        })
        del values, visual, sample
        gc.collect()
        done()

    if "biome_regions" in manifest and (root / manifest["biome_regions"]["path"]).exists():
        report("biome_regions")
        done = timed("biome_regions")
        values = read_cell_u8_preview(root, manifest, "biome_regions", scale, missing_ok=False)
        visual = _fit_image(_categorical_overlay_image(values, manifest["biome_regions"].get("classes", {}), alpha=150, transparent_zero=True), base_size)
        sample = _fit_image(Image.fromarray(values, mode="L"), base_size)
        mips = _save_visual_layer(
            out,
            visual,
            _visual_layer_path("layers/biome_regions", visual_format),
            tile_size=preview_tile_size,
            visual_format=visual_format,
            workers=encoder_workers,
            force=force,
            resampling=Image.Resampling.NEAREST,
        )
        sample.save(out / "samples" / "biome_regions_u8.png")
        layers.append({
            "name": "biome_regions",
            "kind": "overlay",
            "label": "Biome Regions",
            "file": mips[0]["file"],
            "mips": mips,
            "sample_file": "samples/biome_regions_u8.png",
            "sample_tiles": _save_sample_tiles(out, sample, "samples/biome_regions_u8.png", tile_size=preview_tile_size, encoding="u8", workers=encoder_workers, force=force),
        })
        del values, visual, sample
        gc.collect()
        done()

    if "rivers" in manifest and (root / manifest["rivers"]["path"]).exists():
        report("rivers")
        done = timed("rivers")
        values = _read_u8_preview(root, manifest["rivers"]["path"], tiles_x, tiles_z, source_tile_size, scale, missing_ok=False)
        width_values, width_metadata = _read_river_width_preview(root, manifest, tiles_x, tiles_z, source_tile_size, scale)
        visual = _fit_image(_river_overlay_image(values, width_values, width_metadata["source"]), base_size)
        sample = _fit_image(Image.fromarray(values, mode="L"), base_size)
        mips = _save_visual_layer(
            out,
            visual,
            _visual_layer_path("layers/rivers", visual_format),
            tile_size=preview_tile_size,
            visual_format=visual_format,
            workers=encoder_workers,
            force=force,
            resampling=Image.Resampling.BILINEAR,
        )
        sample.save(out / "samples" / "rivers_u8.png")
        layer_entry = {
            "name": "rivers",
            "kind": "overlay",
            "file": mips[0]["file"],
            "mips": mips,
            "sample_file": "samples/rivers_u8.png",
            "sample_tiles": _save_sample_tiles(out, sample, "samples/rivers_u8.png", tile_size=preview_tile_size, encoding="u8", workers=encoder_workers, force=force),
            "preview": width_metadata,
        }
        layers.append(layer_entry)
        del values, width_values, visual, sample
        gc.collect()
        done()

    ore_dir = out / "layers" / "ores"
    ore_sample_dir = out / "samples" / "ores"
    ore_dir.mkdir(parents=True, exist_ok=True)
    ore_sample_dir.mkdir(parents=True, exist_ok=True)
    ore_layers: list[dict[str, Any]] = []
    for ore, layer in manifest.get("ore_layers", {}).items():
        if ore == "tin":
            continue
        if not (root / layer["path"]).exists():
            continue
        report(f"ore:{ore}")
        done = timed(f"ore:{ore}")
        values = _read_u8_preview(root, layer["path"], tiles_x, tiles_z, source_tile_size, scale, missing_ok=True)
        visual = _fit_image(_ore_overlay_image(values, ore), base_size)
        sample = _fit_image(Image.fromarray(values, mode="L"), base_size)
        sample_path = ore_sample_dir / f"{ore}_u8.png"
        mips = _save_visual_layer(
            out,
            visual,
            _visual_layer_path(f"layers/ores/{ore}", visual_format),
            tile_size=preview_tile_size,
            visual_format=visual_format,
            workers=encoder_workers,
            force=force,
            resampling=Image.Resampling.BILINEAR,
        )
        sample.save(sample_path)
        sample_file = f"samples/ores/{ore}_u8.png"
        ore_layers.append(
            {
                "name": f"ore:{ore}",
                "ore": ore,
                "kind": "ore",
                "file": mips[0]["file"],
                "mips": mips,
                "sample_file": sample_file,
                "sample_tiles": _save_sample_tiles(out, sample, sample_file, tile_size=preview_tile_size, encoding="u8", workers=encoder_workers, force=force),
            }
        )
        del values, visual, sample
        gc.collect()
        done()
    layers.extend(ore_layers)

    report("manifest")
    world = manifest["world"]
    geo = manifest.get("georeferencing", {})
    index = {
        "format": HOVER_PREVIEW_FORMAT,
        "scale": scale,
        "max_size": max_size,
        "style": style,
        "tile_pyramid": {
            "tile_size": preview_tile_size,
            "visual_format": visual_format,
            "sample_format": "png",
        },
        "tile_size": source_tile_size,
        "image_width": base_size[0],
        "image_height": base_size[1],
        "world_width": world.get("width"),
        "world_depth": world.get("depth"),
        "minecraft_min_x": world.get("minecraft_min_x"),
        "minecraft_min_z": world.get("minecraft_min_z"),
        "bng_min_easting": geo.get("bng_min_easting"),
        "bng_min_northing": geo.get("bng_min_northing"),
        "bng_max_easting": geo.get("bng_max_easting"),
        "bng_max_northing": geo.get("bng_max_northing"),
        "world": world,
        "georeferencing": geo,
        "minecraft_origin": _minecraft_origin(manifest),
        "height_processing": manifest.get("height_processing", {}),
        "height_overlays": manifest.get("height_overlays", []),
        "surface_geology": manifest.get("surface_geology", {}),
        "vegetation": manifest.get("vegetation", {}),
        "biome_regions": manifest.get("biome_regions", {}),
        "preview": {
            "river_width_source": next((layer["preview"]["source"] for layer in layers if layer["name"] == "rivers"), None),
            "river_width_scale": PREVIEW_RIVER_WIDTH_SCALE,
            "river_min_radius": PREVIEW_RIVER_MIN_RADIUS,
            "river_max_radius": PREVIEW_RIVER_MAX_RADIUS,
        },
        "layers": layers,
    }
    index["generation"] = {
        "tile_size": preview_tile_size,
        "workers": encoder_workers,
        "visual_format": visual_format,
        "force": force,
        "clean_stale": clean_stale,
    }
    if profile:
        index["generation"]["timings_seconds"] = {step: round(seconds, 3) for step, seconds in timings}
    with (out / HOVER_PREVIEW_INDEX).open("w", encoding="utf-8") as fh:
        json.dump(index, fh, indent=2)
        fh.write("\n")
    _write_cache_metadata(out, index["generation"], layers)
    if clean_stale:
        _clean_stale_outputs(out, layers)
    if profile and progress is None:
        for step, seconds in timings:
            print(f"{step}: {seconds:.3f}s")
    return out


def _height_sample_image(values: np.ndarray) -> Image.Image:
    encoded = values.astype(np.int32) + 32768
    encoded[values == HEIGHT_NODATA] = 0
    return Image.fromarray(np.clip(encoded, 0, 65535).astype(np.uint16), mode="I;16")


def _height_browser_sample_image(values: np.ndarray) -> Image.Image:
    encoded = values.astype(np.int32) + 32768
    encoded[values == HEIGHT_NODATA] = 0
    clipped = np.clip(encoded, 0, 65535).astype(np.uint16)
    rgb = np.zeros((*clipped.shape, 3), dtype=np.uint8)
    rgb[:, :, 0] = (clipped & 0xFF).astype(np.uint8)
    rgb[:, :, 1] = (clipped >> 8).astype(np.uint8)
    return Image.fromarray(rgb, mode="RGB")


def _cupy() -> Any | None:
    global _CUPY_MODULE
    if _CUPY_MODULE is False:
        return None
    if _CUPY_MODULE is not None:
        return _CUPY_MODULE
    mode = os.environ.get("HOVERPREVIEW_GPU", "auto").strip().lower()
    if mode in {"0", "false", "no", "off", "cpu"}:
        _CUPY_MODULE = False
        return None
    try:
        import cupy as cp

        if cp.cuda.runtime.getDeviceCount() <= 0:
            _CUPY_MODULE = False
            return None
    except Exception:
        if mode in {"1", "true", "yes", "on", "gpu"}:
            raise
        _CUPY_MODULE = False
        return None
    _CUPY_MODULE = cp
    return cp


def _height_image(values: np.ndarray, style: str) -> Image.Image:
    cp = _cupy()
    if cp is None:
        return _cpu_height_image(values, style)
    try:
        gpu_values = cp.asarray(values)
        valid = gpu_values != HEIGHT_NODATA
        if not bool(cp.any(valid).get()):
            return Image.fromarray(np.zeros(values.shape, dtype=np.uint8), mode="L")
        metres = gpu_values.astype(cp.float32) * cp.float32(0.1)
        valid_values = metres[valid]
        lo = cp.percentile(valid_values, 1)
        hi = cp.percentile(valid_values, 99)
        norm = cp.clip((metres - lo) / cp.maximum(cp.float32(1.0), hi - lo), 0.0, 1.0)
        if style == "gray":
            gray = (norm * 255).astype(cp.uint8)
            gray[~valid] = 0
            return Image.fromarray(cp.asnumpy(gray), mode="L")
        rgb = _apply_ramp_gpu(
            norm,
            [
                (0.00, (35, 85, 45)),
                (0.35, (92, 139, 63)),
                (0.58, (176, 160, 92)),
                (0.78, (125, 96, 69)),
                (1.00, (238, 238, 228)),
            ],
            cp,
        )
        rgb[~valid] = cp.asarray((16, 24, 32), dtype=cp.uint8)
        return Image.fromarray(cp.asnumpy(rgb), mode="RGB")
    except Exception:
        if os.environ.get("HOVERPREVIEW_GPU", "auto").strip().lower() in {"1", "true", "yes", "on", "gpu"}:
            raise
        return _cpu_height_image(values, style)


def _apply_ramp_gpu(norm: Any, stops: list[tuple[float, tuple[int, int, int]]], cp: Any) -> Any:
    rgb = cp.zeros((*norm.shape, 3), dtype=cp.float32)
    for index in range(len(stops) - 1):
        start_pos, start_color = stops[index]
        end_pos, end_color = stops[index + 1]
        mask = (norm >= start_pos) & (norm <= end_pos)
        t = (norm[mask] - start_pos) / max(0.0001, end_pos - start_pos)
        start = cp.asarray(start_color, dtype=cp.float32)
        end = cp.asarray(end_color, dtype=cp.float32)
        rgb[mask] = start + (end - start) * t[:, None]
    rgb[norm <= stops[0][0]] = stops[0][1]
    rgb[norm >= stops[-1][0]] = stops[-1][1]
    return cp.clip(rgb, 0, 255).astype(cp.uint8)


def _minecraft_origin(manifest: dict[str, Any]) -> dict[str, Any]:
    world = manifest["world"]
    geo = manifest.get("georeferencing", {})
    origin: dict[str, Any] = {
        "minecraft_x": 0,
        "minecraft_z": 0,
        "data_x": 0 - int(world["minecraft_min_x"]),
        "data_z": 0 - int(world["minecraft_min_z"]),
    }
    min_e = geo.get("bng_min_easting")
    max_e = geo.get("bng_max_easting")
    min_n = geo.get("bng_min_northing")
    max_n = geo.get("bng_max_northing")
    if min_e is not None and max_e is not None and min_n is not None and max_n is not None:
        origin["bng_easting"] = float(min_e) + (origin["data_x"] + 0.5) * (float(max_e) - float(min_e)) / int(
            world["width"]
        )
        origin["bng_northing"] = float(max_n) - (origin["data_z"] + 0.5) * (float(max_n) - float(min_n)) / int(
            world["depth"]
        )
    return origin


def _validate_tile_size(value: int) -> int:
    tile_size = int(value)
    if tile_size <= 0:
        raise ValueError("--tile-size must be positive")
    return tile_size


def _resolve_workers(value: int | None) -> int:
    if value is None or value <= 0:
        return max(1, min(8, os.cpu_count() or 1))
    return max(1, int(value))


def _visual_layer_path(stem: str, visual_format: str) -> str:
    return f"{stem}.{visual_format}"


def _save_visual_layer(
    root: Path,
    image: Image.Image,
    relative_path: str,
    *,
    tile_size: int = DEFAULT_TILE_SIZE,
    visual_format: str = "png",
    workers: int = 1,
    force: bool = True,
    resampling: Image.Resampling = Image.Resampling.BILINEAR,
) -> list[dict[str, Any]]:
    path = root / relative_path
    path.parent.mkdir(parents=True, exist_ok=True)
    _save_image(path, image, visual_format, force=force)
    mips: list[dict[str, Any]] = [
        _visual_mip_entry(root, image, relative_path, factor=1, file=relative_path, tile_size=tile_size, visual_format=visual_format, workers=workers, force=force)
    ]
    factor = 2
    current = image
    while max(current.size) > 512:
        size = (max(1, math.ceil(image.width / factor)), max(1, math.ceil(image.height / factor)))
        current = image.resize(size, resampling)
        mip_path = root / "mips" / str(factor) / relative_path
        mip_path.parent.mkdir(parents=True, exist_ok=True)
        _save_image(mip_path, current, visual_format, force=force)
        mips.append(
            _visual_mip_entry(root, current, relative_path, factor=factor, file=f"mips/{factor}/{relative_path}", tile_size=tile_size, visual_format=visual_format, workers=workers, force=force)
        )
        factor *= 2
    return mips


def _visual_mip_entry(
    root: Path,
    image: Image.Image,
    relative_path: str,
    *,
    factor: int,
    file: str,
    tile_size: int = DEFAULT_TILE_SIZE,
    visual_format: str = "png",
    workers: int = 1,
    force: bool = True,
) -> dict[str, Any]:
    tile_template = _save_visual_tiles(root, image, relative_path, factor, tile_size=tile_size, visual_format=visual_format, workers=workers, force=force)
    return {
        "factor": factor,
        "file": file,
        "width": image.width,
        "height": image.height,
        "tiles": {
            "size": tile_size,
            "template": tile_template,
            "columns": math.ceil(image.width / tile_size),
            "rows": math.ceil(image.height / tile_size),
            "format": visual_format,
        },
    }


def _save_visual_tiles(
    root: Path,
    image: Image.Image,
    relative_path: str,
    factor: int,
    *,
    tile_size: int = DEFAULT_TILE_SIZE,
    visual_format: str = "png",
    workers: int = 1,
    force: bool = True,
) -> str:
    stem = str(Path(relative_path).with_suffix(""))
    tile_dir = root / "tiles" / str(factor) / stem
    tile_dir.mkdir(parents=True, exist_ok=True)
    jobs = []
    for top in range(0, image.height, tile_size):
        tile_z = top // tile_size
        for left in range(0, image.width, tile_size):
            tile_x = left // tile_size
            box = (left, top, min(image.width, left + tile_size), min(image.height, top + tile_size))
            jobs.append((box, tile_dir / f"{tile_x}_{tile_z}.{visual_format}"))
    _save_tiles(image, jobs, visual_format, workers=workers, force=force)
    return f"tiles/{factor}/{stem}/{{x}}_{{y}}.{visual_format}"


def _save_sample_tiles(
    root: Path,
    image: Image.Image,
    relative_path: str,
    *,
    tile_size: int = DEFAULT_TILE_SIZE,
    encoding: str = "u8",
    workers: int = 1,
    force: bool = True,
) -> dict[str, Any]:
    path = Path(relative_path)
    if path.parts and path.parts[0] == "samples":
        path = Path(*path.parts[1:])
    stem = str(path.with_suffix(""))
    tile_dir = root / "sample_tiles" / stem
    tile_dir.mkdir(parents=True, exist_ok=True)
    jobs = []
    for top in range(0, image.height, tile_size):
        tile_z = top // tile_size
        for left in range(0, image.width, tile_size):
            tile_x = left // tile_size
            box = (left, top, min(image.width, left + tile_size), min(image.height, top + tile_size))
            jobs.append((box, tile_dir / f"{tile_x}_{tile_z}.png"))
    _save_tiles(image, jobs, "png", workers=workers, force=force)
    return {
        "size": tile_size,
        "template": f"sample_tiles/{stem}/{{x}}_{{y}}.png",
        "columns": math.ceil(image.width / tile_size),
        "rows": math.ceil(image.height / tile_size),
        "encoding": encoding,
        "format": "png",
    }


def _save_tiles(
    image: Image.Image,
    jobs: list[tuple[tuple[int, int, int, int], Path]],
    image_format: str,
    *,
    workers: int = 1,
    force: bool = True,
) -> None:
    if workers <= 1 or len(jobs) <= 1:
        for box, path in jobs:
            _save_tile(image, box, path, image_format, force=force)
        return
    with ThreadPoolExecutor(max_workers=workers) as executor:
        list(executor.map(lambda job: _save_tile(image, job[0], job[1], image_format, force=force), jobs))


def _save_tile(image: Image.Image, box: tuple[int, int, int, int], path: Path, image_format: str, *, force: bool) -> None:
    if path.exists() and not force:
        return
    tile = image.crop(box)
    _save_image(path, tile, image_format, force=force)


def _save_image(path: Path, image: Image.Image, image_format: str, *, force: bool) -> None:
    if path.exists() and not force:
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    if image_format == "webp":
        image.save(path, format="WEBP", lossless=False, quality=82, method=4)
    else:
        image.save(path, format="PNG")


def _write_cache_metadata(out: Path, generation: dict[str, Any], layers: list[dict[str, Any]]) -> None:
    files: list[str] = [HOVER_PREVIEW_INDEX]
    for layer in layers:
        files.append(layer["file"])
        files.extend(mip["file"] for mip in layer.get("mips", []))
        if layer.get("sample_file"):
            files.append(layer["sample_file"])
        if layer.get("browser_sample_file"):
            files.append(layer["browser_sample_file"])
    with (out / ".hoverpreview_cache.json").open("w", encoding="utf-8") as fh:
        json.dump({"generation": generation, "files": sorted(set(files))}, fh, indent=2)
        fh.write("\n")


def _clean_stale_outputs(out: Path, layers: list[dict[str, Any]]) -> None:
    keep_templates: set[str] = set()
    keep_files: set[Path] = {out / HOVER_PREVIEW_INDEX, out / ".hoverpreview_cache.json"}
    for layer in layers:
        keep_files.add(out / layer["file"])
        if layer.get("sample_file"):
            keep_files.add(out / layer["sample_file"])
        if layer.get("browser_sample_file"):
            keep_files.add(out / layer["browser_sample_file"])
        for mip in layer.get("mips", []):
            keep_files.add(out / mip["file"])
            if mip.get("tiles", {}).get("template"):
                keep_templates.add(str(mip["tiles"]["template"]).split("{x}")[0])
        if layer.get("sample_tiles", {}).get("template"):
            keep_templates.add(str(layer["sample_tiles"]["template"]).split("{x}")[0])
    for folder in (out / "tiles", out / "sample_tiles"):
        if not folder.exists():
            continue
        for path in folder.rglob("*"):
            if path.is_dir():
                continue
            rel = path.relative_to(out).as_posix()
            if path not in keep_files and not any(rel.startswith(prefix) for prefix in keep_templates):
                path.unlink(missing_ok=True)


def _fit_image(image: Image.Image, size: tuple[int, int]) -> Image.Image:
    if image.size == size:
        return image
    out = Image.new(image.mode, size, 0)
    out.paste(image.crop((0, 0, min(image.width, size[0]), min(image.height, size[1]))), (0, 0))
    return out


def _categorical_overlay_image(values: np.ndarray, classes: dict, *, alpha: int, transparent_zero: bool) -> Image.Image:
    cp = _cupy()
    if cp is not None:
        try:
            gpu_values = cp.asarray(values)
            rgba = cp.zeros((*values.shape, 4), dtype=cp.uint8)
            for raw_id, meta in classes.items():
                class_id = int(raw_id)
                if transparent_zero and class_id == 0:
                    continue
                mask = gpu_values == class_id
                if not bool(cp.any(mask).get()):
                    continue
                rgba[mask, :3] = cp.asarray(_hex_color(meta.get("color", "#777777")), dtype=cp.uint8)
                rgba[mask, 3] = alpha
            return Image.fromarray(cp.asnumpy(rgba), mode="RGBA")
        except Exception:
            if os.environ.get("HOVERPREVIEW_GPU", "auto").strip().lower() in {"1", "true", "yes", "on", "gpu"}:
                raise
    rgba = np.zeros((*values.shape, 4), dtype=np.uint8)
    for raw_id, meta in classes.items():
        class_id = int(raw_id)
        if transparent_zero and class_id == 0:
            continue
        mask = values == class_id
        if not np.any(mask):
            continue
        rgba[mask, :3] = _hex_color(meta.get("color", "#777777"))
        rgba[mask, 3] = alpha
    return Image.fromarray(rgba, mode="RGBA")


def _read_river_width_preview(
    root: Path,
    manifest: dict[str, Any],
    tiles_x: int,
    tiles_z: int,
    tile_size: int,
    scale: int,
) -> tuple[np.ndarray | None, dict[str, Any]]:
    rivers = manifest.get("rivers", {})
    candidates = (
        ("river_half_width", rivers.get("half_width_path") or rivers.get("river_half_width_path")),
        ("river_order", rivers.get("order_path") or rivers.get("river_order_path")),
    )
    metadata: dict[str, Any] = {
        "source": "river_mask",
        "river_half_width_available": False,
        "river_order_available": False,
        "river_width_scale": PREVIEW_RIVER_WIDTH_SCALE,
        "river_min_radius": PREVIEW_RIVER_MIN_RADIUS,
        "river_max_radius": PREVIEW_RIVER_MAX_RADIUS,
    }
    available_layers: list[tuple[str, str]] = []
    for source, layer_path in candidates:
        if not layer_path:
            continue
        available = (root / str(layer_path)).exists()
        if source == "river_half_width":
            metadata["river_half_width_available"] = available
        elif source == "river_order":
            metadata["river_order_available"] = available
        if not available:
            continue
        available_layers.append((source, str(layer_path)))
    if available_layers:
        source, layer_path = available_layers[0]
        values = _read_u8_preview(root, layer_path, tiles_x, tiles_z, tile_size, scale, missing_ok=True)
        metadata["source"] = source
        metadata["path"] = layer_path
        metadata["max_value"] = int(values.max()) if values.size else 0
        return values, metadata
    return None, metadata


def _river_overlay_image(river_mask: np.ndarray, width_values: np.ndarray | None, width_source: str) -> Image.Image:
    radii = _river_preview_radii(river_mask, width_values, width_source)
    if not np.any(radii > 0):
        return Image.fromarray(np.zeros((*river_mask.shape, 4), dtype=np.uint8), mode="RGBA")
    alpha = _dilate_river_radii(radii)
    rgba = np.zeros((*river_mask.shape, 4), dtype=np.uint8)
    rgba[:, :, :3] = np.array(PREVIEW_RIVER_COLOR, dtype=np.uint8)
    rgba[:, :, 3] = alpha
    return Image.fromarray(rgba, mode="RGBA")


def _river_preview_radii(river_mask: np.ndarray, width_values: np.ndarray | None, width_source: str) -> np.ndarray:
    river = river_mask > 0
    radii = np.zeros(river_mask.shape, dtype=np.uint8)
    if width_values is None or width_source == "river_mask":
        radii[river] = PREVIEW_RIVER_MIN_RADIUS
        return radii

    values = _fit_array(width_values, river_mask.shape)
    if width_source == "river_half_width":
        scaled = np.rint(values.astype(np.float32) * PREVIEW_RIVER_WIDTH_SCALE).astype(np.int16)
    elif width_source == "river_order":
        scaled = _river_order_radii(values)
    else:
        scaled = np.where(values > 0, PREVIEW_RIVER_MIN_RADIUS, 0)
    scaled = np.clip(scaled, PREVIEW_RIVER_MIN_RADIUS, PREVIEW_RIVER_MAX_RADIUS).astype(np.uint8)
    radii[river] = scaled[river]
    radii[river & (values == 0)] = PREVIEW_RIVER_MIN_RADIUS
    return radii


def _river_order_radii(order_values: np.ndarray) -> np.ndarray:
    radii = np.zeros(order_values.shape, dtype=np.uint8)
    radii[(order_values == 1) | (order_values == 2)] = 1
    radii[order_values == 3] = 2
    radii[order_values == 4] = 3
    radii[order_values == 5] = 5
    radii[order_values >= 6] = 6
    return radii


def _dilate_river_radii(radii: np.ndarray) -> np.ndarray:
    alpha = np.zeros(radii.shape, dtype=np.uint8)
    for radius in sorted(int(value) for value in np.unique(radii) if value > 0):
        mask = radii == radius
        if not np.any(mask):
            continue
        dilated = _dilate_mask(mask, radius)
        alpha[dilated] = 228
    return alpha


def _dilate_mask(mask: np.ndarray, radius: int) -> np.ndarray:
    if radius <= 0:
        return mask.copy()
    out = np.zeros(mask.shape, dtype=bool)
    offsets = _circle_offsets(radius)
    height, width = mask.shape
    for dy, dx in offsets:
        src_y0 = max(0, -dy)
        src_y1 = min(height, height - dy)
        src_x0 = max(0, -dx)
        src_x1 = min(width, width - dx)
        if src_y0 >= src_y1 or src_x0 >= src_x1:
            continue
        dst_y0 = src_y0 + dy
        dst_y1 = src_y1 + dy
        dst_x0 = src_x0 + dx
        dst_x1 = src_x1 + dx
        out[dst_y0:dst_y1, dst_x0:dst_x1] |= mask[src_y0:src_y1, src_x0:src_x1]
    return out


def _circle_offsets(radius: int) -> list[tuple[int, int]]:
    radius_sq = radius * radius
    return [
        (dy, dx)
        for dy in range(-radius, radius + 1)
        for dx in range(-radius, radius + 1)
        if dy * dy + dx * dx <= radius_sq
    ]


def _fit_array(values: np.ndarray, shape: tuple[int, int]) -> np.ndarray:
    if values.shape == shape:
        return values
    out = np.zeros(shape, dtype=values.dtype)
    height = min(shape[0], values.shape[0])
    width = min(shape[1], values.shape[1])
    out[:height, :width] = values[:height, :width]
    return out


def _mask_overlay_image(values: np.ndarray, color_value: tuple[int, int, int]) -> Image.Image:
    cp = _cupy()
    if cp is not None:
        try:
            gpu_values = cp.asarray(values)
            score = gpu_values.astype(cp.float32) / cp.float32(255.0)
            rgba = cp.zeros((*values.shape, 4), dtype=cp.uint8)
            rgba[:, :, :3] = cp.asarray(color_value, dtype=cp.uint8)
            rgba[:, :, 3] = cp.clip(score * 240, 0, 240).astype(cp.uint8)
            return Image.fromarray(cp.asnumpy(rgba), mode="RGBA")
        except Exception:
            if os.environ.get("HOVERPREVIEW_GPU", "auto").strip().lower() in {"1", "true", "yes", "on", "gpu"}:
                raise
    score = values.astype(np.float32) / 255.0
    rgba = np.zeros((*values.shape, 4), dtype=np.uint8)
    rgba[:, :, :3] = np.array(color_value, dtype=np.uint8)
    rgba[:, :, 3] = np.clip(score * 240, 0, 240).astype(np.uint8)
    return Image.fromarray(rgba, mode="RGBA")


def _ore_overlay_image(values: np.ndarray, ore: str) -> Image.Image:
    cp = _cupy()
    if cp is not None:
        try:
            gpu_values = cp.asarray(values)
            score = gpu_values.astype(cp.float32) / cp.float32(255.0)
            color = cp.asarray(ORE_COLORS.get(ore, (255, 255, 255)), dtype=cp.uint8)
            rgba = cp.zeros((*values.shape, 4), dtype=cp.uint8)
            rgba[:, :, :3] = color
            rgba[:, :, 3] = cp.clip(score * 216, 0, 230).astype(cp.uint8)
            return Image.fromarray(cp.asnumpy(rgba), mode="RGBA")
        except Exception:
            if os.environ.get("HOVERPREVIEW_GPU", "auto").strip().lower() in {"1", "true", "yes", "on", "gpu"}:
                raise
    score = values.astype(np.float32) / 255.0
    color = np.array(ORE_COLORS.get(ore, (255, 255, 255)), dtype=np.float32)
    rgba = np.zeros((*values.shape, 4), dtype=np.uint8)
    rgba[:, :, :3] = color.astype(np.uint8)
    rgba[:, :, 3] = np.clip(score * 216, 0, 230).astype(np.uint8)
    return Image.fromarray(rgba, mode="RGBA")
