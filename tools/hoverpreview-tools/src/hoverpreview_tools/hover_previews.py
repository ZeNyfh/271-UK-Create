from __future__ import annotations

from pathlib import Path
from dataclasses import dataclass
import gc
import json
import math
import os
import shutil
import tempfile
import time
from concurrent.futures import ThreadPoolExecutor
from collections.abc import Callable
from typing import Any

import numpy as np
from PIL import Image

from ukgeo.config import get_config_value
from ukgeo.manifest import read_manifest
from ukgeo.preview import ORE_COLORS, _height_image as _cpu_height_image, _hex_color, _read_height_preview, _read_u8_preview, read_cell_u8_preview, read_vegetation_preview
from ukgeo.tiles import HEIGHT_NODATA, river_u8_layer
from .weather_overlay import DEFAULT_OPEN_METEO_BASE_URL, build_weather_overlay_grid


HOVER_PREVIEW_FORMAT = "ukgeo-hoverpreviews-v1"
HOVER_PREVIEW_INDEX = "hover_manifest.json"
DEFAULT_TILE_SIZE = 256
VISUAL_TILE_SIZE = DEFAULT_TILE_SIZE
SUPPORTED_VISUAL_FORMATS = {"png", "webp"}
PREVIEW_RIVER_WIDTH_SCALE = 0.18
PREVIEW_RIVER_MIN_RADIUS = 1
PREVIEW_RIVER_MAX_RADIUS = 6
PREVIEW_RIVER_COLOR = (65, 145, 230)
RIVER_MIP_ALPHA_GAMMA = 2.4
RIVER_MIP_ALPHA_CUTOFF = 18
DEFAULT_TILE_BATCH_ROWS = 4
_CUPY_MODULE: Any | None | bool = None
ANIMAL_LAYER_COLORS: dict[str, tuple[int, int, int]] = {
    "wildernature:deer": (121, 76, 46),
    "wildernature:bison": (88, 55, 34),
    "wildernature:boar": (142, 92, 58),
    "wildernature:hedgehog": (120, 92, 70),
    "wildernature:minisheep": (194, 194, 201),
    "wildernature:owl": (156, 116, 61),
    "wildernature:squirrel": (114, 46, 38),
    "minecraft:bat": (120, 110, 105),
    "minecraft:cow": (178, 112, 52),
    "minecraft:sheep": (214, 202, 196),
    "minecraft:pig": (188, 114, 76),
    "minecraft:chicken": (232, 190, 109),
    "minecraft:rabbit": (66, 120, 78),
    "minecraft:wolf": (108, 116, 116),
    "minecraft:fox": (191, 72, 49),
}

CLOUD_OVERLAY_MIN_SHADE = 108
CLOUD_OVERLAY_MAX_SHADE = 232
CLOUD_OVERLAY_MAX_ALPHA = 176
DOWNFALL_OVERLAY_COLOR = (76, 148, 255)
DOWNFALL_OVERLAY_MAX_ALPHA = 208
DOWNFALL_OVERLAY_MAX_MM = 5.0


@dataclass(frozen=True)
class DiskRaster:
    path: Path
    mode: str
    width: int
    height: int
    dtype: Any
    shape: tuple[int, ...]


def _hoverpreview_gpu_mode() -> str:
    config_path = Path(__file__).resolve().parents[2] / "config.yml"
    return str(get_config_value(config_path, "runtime.HOVERPREVIEW_GPU", "auto")).strip().lower()


def _weather_overlay_settings() -> dict[str, Any]:
    config_path = Path(__file__).resolve().parents[2] / "config.yml"
    return {
        "enabled": bool(get_config_value(config_path, "generate.HOVERPREVIEW_WEATHER_ENABLED", True)),
        "api_base_url": str(get_config_value(config_path, "generate.HOVERPREVIEW_WEATHER_API_BASE_URL", DEFAULT_OPEN_METEO_BASE_URL)).strip(),
        "weather_model": str(get_config_value(config_path, "generate.HOVERPREVIEW_WEATHER_MODEL", "auto")).strip(),
        "timeout_seconds": float(get_config_value(config_path, "generate.HOVERPREVIEW_WEATHER_TIMEOUT_SECONDS", 20)),
        "grid_columns": max(2, int(get_config_value(config_path, "generate.HOVERPREVIEW_WEATHER_GRID_COLUMNS", 32))),
        "batch_points": max(1, int(get_config_value(config_path, "generate.HOVERPREVIEW_WEATHER_BATCH_POINTS", 64))),
    }


def _weather_overlay_enabled() -> bool:
    return bool(_weather_overlay_settings()["enabled"])


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
    for entity_id, layer in manifest.get("animal_habitats", {}).get("entities", {}).items():
        if (root / layer["path"]).exists():
            steps.append(f"animal:{entity_id}")
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
    renderer: str = "auto",
    force: bool = False,
    clean_stale: bool = False,
    deploy_minimal: bool = False,
    profile: bool = False,
    write_full_images: bool = False,
    tile_batch_rows: int = DEFAULT_TILE_BATCH_ROWS,
    progress: Callable[[str], None] | None = None,
    weather_overlay: bool | None = None,
) -> Path:
    manifest = read_manifest(root / "manifest.json")
    source_tile_size = int(manifest["tile_size"])
    preview_tile_size = _validate_tile_size(tile_size)
    encoder_workers = _resolve_workers(workers)
    visual_format = visual_format.lower().strip()
    if visual_format not in SUPPORTED_VISUAL_FORMATS:
        raise ValueError(f"Unsupported visual format {visual_format!r}; expected one of {sorted(SUPPORTED_VISUAL_FORMATS)}")
    renderer = _validate_renderer(renderer)
    tile_batch_rows = max(1, int(tile_batch_rows))
    weather_overlay = _weather_overlay_enabled() if weather_overlay is None else bool(weather_overlay)
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
    height_content_bounds = _content_bounds(height_values, HEIGHT_NODATA)
    with tempfile.TemporaryDirectory(prefix="hoverpreview-height-", dir=out) as temp_dir_name:
        temp_dir = Path(temp_dir_name)
        height_visual = _create_disk_raster(temp_dir / "height.visual.bin", base_size, "RGB")
        _render_height_visual_raster(height_values, style, height_visual)
        height_mips = _save_visual_raster_layer(
            out,
            height_visual,
            "layers/height",
            tile_size=preview_tile_size,
            visual_format=visual_format,
            workers=encoder_workers,
            force=force,
            resampling=Image.Resampling.BILINEAR,
            tile_batch_rows=tile_batch_rows,
            write_full_images=write_full_images,
        )
        height_sample_file = None
        if write_full_images:
            height_sample = _create_disk_raster(temp_dir / "height.sample.bin", base_size, "I;16")
            _render_height_sample_raster(height_values, height_sample)
            height_sample_file = "samples/height_u16.png"
            _save_disk_raster_image(out / height_sample_file, height_sample, "png", force=force)
            _delete_disk_raster(height_sample)
        height_browser_sample = _create_disk_raster(temp_dir / "height.browser.bin", base_size, "RGB")
        _render_height_browser_sample_raster(height_values, height_browser_sample)
        height_browser_sample_file = "samples/height_rgb.png" if write_full_images else None
        if write_full_images and height_browser_sample_file is not None:
            _save_disk_raster_image(out / height_browser_sample_file, height_browser_sample, "png", force=force)
        height_sample_tiles = _save_sample_raster_tiles(
            out,
            height_browser_sample,
            "samples/height_rgb.png",
            tile_size=preview_tile_size,
            encoding="signed-decimetres-rgb-le-offset-32768",
            workers=encoder_workers,
            force=force,
            tile_batch_rows=tile_batch_rows,
        )
        _delete_disk_raster(height_visual)
        _delete_disk_raster(height_browser_sample)
    del height_values
    gc.collect()
    done()

    layers: list[dict[str, Any]] = [
        {
            "name": "height",
            "kind": "base",
            "file": height_mips[0].get("file"),
            "mips": height_mips,
            "sample_file": height_sample_file,
            "browser_sample_file": height_browser_sample_file,
            "browser_sample_encoding": "signed-decimetres-rgb-le-offset-32768",
            "sample_tiles": height_sample_tiles,
        },
    ]

    if "surface_geology" in manifest:
        report("surface")
        done = timed("surface")
        values = _read_u8_preview(root, manifest["surface_geology"], tiles_x, tiles_z, source_tile_size, scale, missing_ok=False)
        with tempfile.TemporaryDirectory(prefix="hoverpreview-surface-", dir=out) as temp_dir_name:
            temp_dir = Path(temp_dir_name)
            visual = _create_disk_raster(temp_dir / "surface.visual.bin", base_size, "RGBA")
            _render_categorical_overlay_raster(values, manifest["surface_geology"].get("classes", {}), alpha=166, transparent_zero=True, raster=visual)
            sample = _create_disk_raster(temp_dir / "surface.sample.bin", base_size, "L")
            _render_fitted_u8_sample_raster(values, sample)
            mips = _save_visual_raster_layer(
                out,
                visual,
                "layers/surface",
                tile_size=preview_tile_size,
                visual_format=visual_format,
                workers=encoder_workers,
                force=force,
                resampling=Image.Resampling.NEAREST,
                tile_batch_rows=tile_batch_rows,
                write_full_images=write_full_images,
            )
            sample_file = "samples/surface_u8.png" if write_full_images else None
            if write_full_images and sample_file is not None:
                _save_disk_raster_image(out / sample_file, sample, "png", force=force)
            sample_tiles = _save_sample_raster_tiles(
                out,
                sample,
                "samples/surface_u8.png",
                tile_size=preview_tile_size,
                encoding="u8",
                workers=encoder_workers,
                force=force,
                tile_batch_rows=tile_batch_rows,
            )
            _delete_disk_raster(visual)
            _delete_disk_raster(sample)
        layers.append({
            "name": "surface",
            "kind": "overlay",
            "file": mips[0].get("file"),
            "mips": mips,
            "sample_file": sample_file,
            "sample_tiles": sample_tiles,
        })
        del values
        gc.collect()
        done()

    if "vegetation" in manifest:
        report("vegetation")
        done = timed("vegetation")
        values = read_vegetation_preview(root, manifest, scale, missing_ok=False)
        with tempfile.TemporaryDirectory(prefix="hoverpreview-vegetation-", dir=out) as temp_dir_name:
            temp_dir = Path(temp_dir_name)
            visual = _create_disk_raster(temp_dir / "vegetation.visual.bin", base_size, "RGBA")
            _render_categorical_overlay_raster(values, manifest["vegetation"].get("classes", {}), alpha=176, transparent_zero=True, raster=visual)
            sample = _create_disk_raster(temp_dir / "vegetation.sample.bin", base_size, "L")
            _render_fitted_u8_sample_raster(values, sample)
            mips = _save_visual_raster_layer(
                out,
                visual,
                "layers/vegetation",
                tile_size=preview_tile_size,
                visual_format=visual_format,
                workers=encoder_workers,
                force=force,
                resampling=Image.Resampling.NEAREST,
                tile_batch_rows=tile_batch_rows,
                write_full_images=write_full_images,
            )
            sample_file = "samples/vegetation_u8.png" if write_full_images else None
            if write_full_images and sample_file is not None:
                _save_disk_raster_image(out / sample_file, sample, "png", force=force)
            sample_tiles = _save_sample_raster_tiles(
                out,
                sample,
                "samples/vegetation_u8.png",
                tile_size=preview_tile_size,
                encoding="u8",
                workers=encoder_workers,
                force=force,
                tile_batch_rows=tile_batch_rows,
            )
            _delete_disk_raster(visual)
            _delete_disk_raster(sample)
        layers.append({
            "name": "vegetation",
            "kind": "overlay",
            "file": mips[0].get("file"),
            "mips": mips,
            "sample_file": sample_file,
            "sample_tiles": sample_tiles,
        })
        del values
        gc.collect()
        done()

    if "biome_regions" in manifest:
        report("biome_regions")
        done = timed("biome_regions")
        values = read_cell_u8_preview(root, manifest, "biome_regions", scale, missing_ok=False)
        with tempfile.TemporaryDirectory(prefix="hoverpreview-biome-regions-", dir=out) as temp_dir_name:
            temp_dir = Path(temp_dir_name)
            visual_source = _create_disk_raster(temp_dir / "biome_regions.source.bin", (values.shape[1], values.shape[0]), "RGBA")
            _render_categorical_overlay_raster(values, manifest["biome_regions"].get("classes", {}), alpha=150, transparent_zero=True, raster=visual_source)
            visual = _resize_disk_raster(visual_source, temp_dir / "biome_regions.visual.bin", base_size, Image.Resampling.BILINEAR)
            sample = _create_disk_raster(temp_dir / "biome_regions.sample.bin", base_size, "L")
            _render_fitted_u8_sample_raster(values, sample)
            mips = _save_visual_raster_layer(
                out,
                visual,
                "layers/biome_regions",
                tile_size=preview_tile_size,
                visual_format=visual_format,
                workers=encoder_workers,
                force=force,
                resampling=Image.Resampling.NEAREST,
                tile_batch_rows=tile_batch_rows,
                write_full_images=write_full_images,
            )
            sample_file = "samples/biome_regions_u8.png" if write_full_images else None
            if write_full_images and sample_file is not None:
                _save_disk_raster_image(out / sample_file, sample, "png", force=force)
            sample_tiles = _save_sample_raster_tiles(
                out,
                sample,
                "samples/biome_regions_u8.png",
                tile_size=preview_tile_size,
                encoding="u8",
                workers=encoder_workers,
                force=force,
                tile_batch_rows=tile_batch_rows,
            )
            _delete_disk_raster(visual_source)
            _delete_disk_raster(visual)
            _delete_disk_raster(sample)
        layers.append({
            "name": "biome_regions",
            "kind": "overlay",
            "label": "Biome Regions",
            "file": mips[0].get("file"),
            "mips": mips,
            "sample_file": sample_file,
            "sample_tiles": sample_tiles,
        })
        del values
        gc.collect()
        done()

    if "rivers" in manifest:
        report("rivers")
        done = timed("rivers")
        values = _read_u8_preview(root, river_u8_layer(manifest["rivers"]), tiles_x, tiles_z, source_tile_size, scale, missing_ok=False)
        width_values, width_metadata = _read_river_width_preview(root, manifest, tiles_x, tiles_z, source_tile_size, scale)
        with tempfile.TemporaryDirectory(prefix="hoverpreview-rivers-", dir=out) as temp_dir_name:
            temp_dir = Path(temp_dir_name)
            visual = _create_disk_raster(temp_dir / "rivers.visual.bin", base_size, "RGBA")
            _render_river_overlay_raster(values, width_values, width_metadata["source"], visual)
            sample = _create_disk_raster(temp_dir / "rivers.sample.bin", base_size, "L")
            _render_fitted_u8_sample_raster(values, sample)
            mips = _save_visual_raster_layer(
                out,
                visual,
                "layers/rivers",
                tile_size=preview_tile_size,
                visual_format=visual_format,
                workers=encoder_workers,
                force=force,
                resampling=Image.Resampling.BILINEAR,
                mip_alpha_gamma=RIVER_MIP_ALPHA_GAMMA,
                mip_alpha_cutoff=RIVER_MIP_ALPHA_CUTOFF,
                mip_alpha_transform_min_factor=4,
                tile_batch_rows=tile_batch_rows,
                write_full_images=write_full_images,
            )
            sample_file = "samples/rivers_u8.png" if write_full_images else None
            if write_full_images and sample_file is not None:
                _save_disk_raster_image(out / sample_file, sample, "png", force=force)
            sample_tiles = _save_sample_raster_tiles(
                out,
                sample,
                "samples/rivers_u8.png",
                tile_size=preview_tile_size,
                encoding="u8",
                workers=encoder_workers,
                force=force,
                tile_batch_rows=tile_batch_rows,
            )
            _delete_disk_raster(visual)
            _delete_disk_raster(sample)
        layer_entry = {
            "name": "rivers",
            "kind": "overlay",
            "file": mips[0].get("file"),
            "mips": mips,
            "sample_file": sample_file,
            "sample_tiles": sample_tiles,
            "preview": width_metadata,
        }
        layers.append(layer_entry)
        del values, width_values
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
        report(f"ore:{ore}")
        done = timed(f"ore:{ore}")
        values = _read_u8_preview(root, layer, tiles_x, tiles_z, source_tile_size, scale, missing_ok=True)
        if not np.any(values):
            del values
            done()
            continue
        with tempfile.TemporaryDirectory(prefix=f"hoverpreview-ore-{ore}-", dir=out) as temp_dir_name:
            temp_dir = Path(temp_dir_name)
            visual = _create_disk_raster(temp_dir / f"{ore}.visual.bin", base_size, "RGBA")
            _render_ore_overlay_raster(values, ore, visual)
            sample = _create_disk_raster(temp_dir / f"{ore}.sample.bin", base_size, "L")
            _render_fitted_u8_sample_raster(values, sample)
            mips = _save_visual_raster_layer(
                out,
                visual,
                f"layers/ores/{ore}",
                tile_size=preview_tile_size,
                visual_format=visual_format,
                workers=encoder_workers,
                force=force,
                resampling=Image.Resampling.NEAREST,
                tile_batch_rows=tile_batch_rows,
                write_full_images=write_full_images,
            )
            sample_file = f"samples/ores/{ore}_u8.png" if write_full_images else None
            if write_full_images and sample_file is not None:
                _save_disk_raster_image(ore_sample_dir / f"{ore}_u8.png", sample, "png", force=force)
            sample_tiles = _save_sample_raster_tiles(
                out,
                sample,
                f"samples/ores/{ore}_u8.png",
                tile_size=preview_tile_size,
                encoding="u8",
                workers=encoder_workers,
                force=force,
                tile_batch_rows=tile_batch_rows,
            )
            _delete_disk_raster(visual)
            _delete_disk_raster(sample)
        ore_layers.append(
            {
                "name": f"ore:{ore}",
                "ore": ore,
                "kind": "ore",
                "file": mips[0].get("file"),
                "mips": mips,
                "sample_file": sample_file,
                "sample_tiles": sample_tiles,
            }
        )
        del values
        gc.collect()
        done()
    layers.extend(ore_layers)

    animal_dir = out / "layers" / "animals"
    animal_sample_dir = out / "samples" / "animals"
    animal_dir.mkdir(parents=True, exist_ok=True)
    animal_sample_dir.mkdir(parents=True, exist_ok=True)
    animal_layers: list[dict[str, Any]] = []
    for entity_id, layer in manifest.get("animal_habitats", {}).get("entities", {}).items():
        layer_path = layer.get("path")
        if not layer_path:
            continue
        report(f"animal:{entity_id}")
        done = timed(f"animal:{entity_id}")
        values = _read_u8_preview(root, layer, tiles_x, tiles_z, source_tile_size, scale, missing_ok=True)
        if not np.any(values):
            del values
            done()
            continue
        with tempfile.TemporaryDirectory(prefix=f"hoverpreview-animal-{entity_id.replace(':', '_')}-", dir=out) as temp_dir_name:
            temp_dir = Path(temp_dir_name)
            visual = _create_disk_raster(temp_dir / f"{entity_id.replace(':', '_')}.visual.bin", base_size, "RGBA")
            _render_animal_overlay_raster(values, entity_id, visual)
            sample = _create_disk_raster(temp_dir / f"{entity_id.replace(':', '_')}.sample.bin", base_size, "L")
            _render_fitted_u8_sample_raster(values, sample)
            relative_stem = f"layers/animals/{entity_id.replace(':', '__')}"
            mips = _save_visual_raster_layer(
                out,
                visual,
                relative_stem,
                tile_size=preview_tile_size,
                visual_format=visual_format,
                workers=encoder_workers,
                force=force,
                resampling=Image.Resampling.BILINEAR,
                tile_batch_rows=tile_batch_rows,
                write_full_images=write_full_images,
            )
            sample_file = f"samples/animals/{entity_id.replace(':', '__')}_u8.png" if write_full_images else None
            if write_full_images and sample_file is not None:
                _save_disk_raster_image(animal_sample_dir / f"{entity_id.replace(':', '__')}_u8.png", sample, "png", force=force)
            sample_tiles = _save_sample_raster_tiles(
                out,
                sample,
                f"samples/animals/{entity_id.replace(':', '__')}_u8.png",
                tile_size=preview_tile_size,
                encoding="u8",
                workers=encoder_workers,
                force=force,
                tile_batch_rows=tile_batch_rows,
            )
            _delete_disk_raster(visual)
            _delete_disk_raster(sample)
        animal_layers.append(
            {
                "name": f"animal:{entity_id}",
                "kind": "animal",
                "entity_id": entity_id,
                "label": _animal_label(entity_id),
                "file": mips[0].get("file"),
                "mips": mips,
                "sample_file": sample_file,
                "sample_tiles": sample_tiles,
            }
        )
        del values
        gc.collect()
        done()
    layers.extend(animal_layers)

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
        "axis_scale": manifest.get("axis_scale", {"x": 1.0, "z": 1.0}),
        "minecraft_origin": _minecraft_origin(manifest),
        "height_processing": manifest.get("height_processing", {}),
        "height_overlays": manifest.get("height_overlays", []),
        "surface_geology": manifest.get("surface_geology", {}),
        "vegetation": manifest.get("vegetation", {}),
        "biome_regions": manifest.get("biome_regions", {}),
        "animal_habitats": manifest.get("animal_habitats", {}),
        "preview": {
            "river_width_source": next((layer["preview"]["source"] for layer in layers if layer["name"] == "rivers"), None),
            "river_width_scale": PREVIEW_RIVER_WIDTH_SCALE,
            "river_min_radius": PREVIEW_RIVER_MIN_RADIUS,
            "river_max_radius": PREVIEW_RIVER_MAX_RADIUS,
        },
        "content_bounds": {
            "height": height_content_bounds,
        },
        "viewer": {
            "renderer_preference": renderer,
        },
        "layers": layers,
    }
    if weather_overlay:
        index["live_weather"] = _live_weather_manifest(manifest)
    index["generation"] = {
        "tile_size": preview_tile_size,
        "workers": encoder_workers,
        "tile_batch_rows": tile_batch_rows,
        "visual_format": visual_format,
        "renderer": renderer,
        "force": force,
        "clean_stale": clean_stale,
        "deploy_minimal": deploy_minimal,
        "write_full_images": write_full_images,
        "cache_buster": str(time.time_ns()),
    }
    if profile:
        index["generation"]["timings_seconds"] = {step: round(seconds, 3) for step, seconds in timings}
    with (out / HOVER_PREVIEW_INDEX).open("w", encoding="utf-8") as fh:
        json.dump(index, fh, indent=2)
        fh.write("\n")
    _write_cache_metadata(out, index["generation"], layers)
    if clean_stale:
        _clean_stale_outputs(out, layers)
    if deploy_minimal:
        _prune_deploy_minimal_outputs(out)
    if profile and progress is None:
        for step, seconds in timings:
            print(f"{step}: {seconds:.3f}s")
    return out


def _live_weather_manifest(manifest: dict[str, Any]) -> dict[str, Any]:
    settings = _weather_overlay_settings()
    grid = build_weather_overlay_grid(manifest, grid_columns=settings["grid_columns"])
    return {
        "provider": "Open-Meteo",
        "api_base_url": settings["api_base_url"],
        "weather_model": settings["weather_model"],
        "timeout_seconds": settings["timeout_seconds"],
        "batch_points": settings["batch_points"],
        "metrics": {
            "cloud_cover": {"unit": "percent", "source": "current.cloud_cover"},
            "downfall_coverage": {"unit": "mm", "source": "current.precipitation"},
        },
        "grid": {
            "rows": grid.rows,
            "columns": grid.columns,
            "latitudes": list(grid.latitudes),
            "longitudes": list(grid.longitudes),
        },
    }


def _export_weather_overlay_layers(
    manifest: dict[str, Any],
    out: Path,
    base_size: tuple[int, int],
    *,
    preview_tile_size: int,
    visual_format: str,
    encoder_workers: int,
    force: bool,
    tile_batch_rows: int,
    write_full_images: bool,
    layers: list[dict[str, Any]],
    fetcher: Callable[..., WeatherOverlaySnapshot],
) -> dict[str, Any]:
    settings = _weather_overlay_settings()
    grid = build_weather_overlay_grid(manifest, grid_columns=settings["grid_columns"])
    snapshot = fetcher(
        grid,
        api_base_url=settings["api_base_url"],
        weather_model=settings["weather_model"],
        timeout_seconds=settings["timeout_seconds"],
        batch_points=settings["batch_points"],
    )
    cloud_layer = _export_numeric_weather_layer(
        out,
        values=snapshot.cloud_cover,
        base_size=base_size,
        layer_name="cloud_cover",
        label="Cloud cover",
        preview_tile_size=preview_tile_size,
        visual_format=visual_format,
        encoder_workers=encoder_workers,
        force=force,
        tile_batch_rows=tile_batch_rows,
        write_full_images=write_full_images,
        render_visual=_render_cloud_overlay_raster,
    )
    downfall_layer = _export_numeric_weather_layer(
        out,
        values=snapshot.downfall_coverage,
        base_size=base_size,
        layer_name="downfall_coverage",
        label="Downfall coverage",
        preview_tile_size=preview_tile_size,
        visual_format=visual_format,
        encoder_workers=encoder_workers,
        force=force,
        tile_batch_rows=tile_batch_rows,
        write_full_images=write_full_images,
        render_visual=_render_downfall_overlay_raster,
    )
    layers.extend([cloud_layer, downfall_layer])
    return {
        "provider": "Open-Meteo",
        "api_base_url": snapshot.api_base_url,
        "weather_model": snapshot.weather_model,
        "grid_rows": snapshot.grid_rows,
        "grid_columns": snapshot.grid_columns,
        "cloud_cover": {
            "metric": "cloud_cover",
            "unit": "percent",
            "observed_at_unix": snapshot.cloud_observed_at_unix,
        },
        "downfall_coverage": {
            "metric": "precipitation",
            "unit": "mm",
            "observed_at_unix": snapshot.downfall_observed_at_unix,
        },
    }


def _export_numeric_weather_layer(
    out: Path,
    *,
    values: np.ndarray,
    base_size: tuple[int, int],
    layer_name: str,
    label: str,
    preview_tile_size: int,
    visual_format: str,
    encoder_workers: int,
    force: bool,
    tile_batch_rows: int,
    write_full_images: bool,
    render_visual: Callable[[np.ndarray, DiskRaster], None],
) -> dict[str, Any]:
    with tempfile.TemporaryDirectory(prefix=f"hoverpreview-{layer_name}-", dir=out) as temp_dir_name:
        temp_dir = Path(temp_dir_name)
        if layer_name == "downfall_coverage":
            sample_values = np.clip(values.astype(np.float32), 0, 255).astype(np.uint8)
            value_format = "precipitation_mm"
        else:
            sample_values = values.astype(np.uint8)
            value_format = "percent"
        sample_source = _create_disk_raster(temp_dir / f"{layer_name}.sample.source.bin", (values.shape[1], values.shape[0]), "L")
        _render_fitted_u8_sample_raster(sample_values, sample_source)
        sample = _resize_disk_raster(sample_source, temp_dir / f"{layer_name}.sample.bin", base_size, Image.Resampling.BILINEAR)

        visual_source = _create_disk_raster(temp_dir / f"{layer_name}.visual.source.bin", (values.shape[1], values.shape[0]), "RGBA")
        render_visual(values, visual_source)
        visual = _resize_disk_raster(visual_source, temp_dir / f"{layer_name}.visual.bin", base_size, Image.Resampling.BILINEAR)

        mips = _save_visual_raster_layer(
            out,
            visual,
            f"layers/weather/{layer_name}",
            tile_size=preview_tile_size,
            visual_format=visual_format,
            workers=encoder_workers,
            force=force,
            resampling=Image.Resampling.BILINEAR,
            tile_batch_rows=tile_batch_rows,
            write_full_images=write_full_images,
        )
        sample_file = f"samples/weather/{layer_name}_u8.png" if write_full_images else None
        if write_full_images and sample_file is not None:
            _save_disk_raster_image(out / sample_file, sample, "png", force=force)
        sample_tiles = _save_sample_raster_tiles(
            out,
            sample,
            f"samples/weather/{layer_name}_u8.png",
            tile_size=preview_tile_size,
            encoding="u8",
            workers=encoder_workers,
            force=force,
            tile_batch_rows=tile_batch_rows,
        )
        _delete_disk_raster(sample_source)
        _delete_disk_raster(sample)
        _delete_disk_raster(visual_source)
        _delete_disk_raster(visual)

    return {
        "name": layer_name,
        "kind": "weather",
        "label": label,
        "value_format": value_format,
        "file": mips[0].get("file"),
        "mips": mips,
        "sample_file": sample_file,
        "sample_tiles": sample_tiles,
    }


def _create_disk_raster(path: Path, size: tuple[int, int], mode: str) -> DiskRaster:
    dtype, channels = _disk_raster_format(mode)
    width, height = int(size[0]), int(size[1])
    shape = (height, width) if channels == 1 else (height, width, channels)
    path.parent.mkdir(parents=True, exist_ok=True)
    raster = np.memmap(path, dtype=dtype, mode="w+", shape=shape)
    raster.flush()
    del raster
    return DiskRaster(path=path, mode=mode, width=width, height=height, dtype=dtype, shape=shape)


def _disk_raster_format(mode: str) -> tuple[Any, int]:
    if mode == "L":
        return np.uint8, 1
    if mode == "RGB":
        return np.uint8, 3
    if mode == "RGBA":
        return np.uint8, 4
    if mode == "I;16":
        return np.uint16, 1
    raise ValueError(f"Unsupported raster mode {mode!r}")


def _open_disk_raster(raster: DiskRaster, *, write: bool = False) -> np.memmap:
    return np.memmap(raster.path, dtype=raster.dtype, mode="r+" if write else "r", shape=raster.shape)


def _delete_disk_raster(raster: DiskRaster) -> None:
    raster.path.unlink(missing_ok=True)


def _save_disk_raster_image(path: Path, raster: DiskRaster, image_format: str, *, force: bool) -> None:
    array = _open_disk_raster(raster)
    try:
        image = Image.fromarray(np.asarray(array), mode=raster.mode)
        _save_image(path, image, image_format, force=force)
    finally:
        del array


def _resize_disk_raster(source: DiskRaster, path: Path, size: tuple[int, int], resampling: Image.Resampling) -> DiskRaster:
    out = _create_disk_raster(path, size, source.mode)
    source_array = _open_disk_raster(source)
    dest_array = _open_disk_raster(out, write=True)
    try:
        source_image = Image.fromarray(np.asarray(source_array), mode=source.mode)
        resized = source_image.resize((out.width, out.height), resampling)
        dest_array[...] = np.asarray(resized, dtype=out.dtype)
        dest_array.flush()
    finally:
        del source_array
        del dest_array
    return out


def _adjust_disk_raster_alpha(raster: DiskRaster, gamma: float, cutoff: int) -> None:
    array = _open_disk_raster(raster, write=True)
    try:
        alpha = np.asarray(array[:, :, 3], dtype=np.float32) / 255.0
        adjusted = np.power(np.clip(alpha, 0.0, 1.0), max(0.01, float(gamma))) * 255.0
        if cutoff > 0:
            adjusted[adjusted < cutoff] = 0.0
        array[:, :, 3] = np.clip(adjusted, 0, 255).astype(np.uint8)
        array.flush()
    finally:
        del array


def _render_height_visual_raster(values: np.ndarray, style: str, raster: DiskRaster) -> None:
    array = _open_disk_raster(raster, write=True)
    try:
        valid = values != HEIGHT_NODATA
        if not np.any(valid):
            array[...] = 0
            array.flush()
            return
        metres = values.astype(np.float32) * 0.1
        valid_values = metres[valid]
        lo = float(np.percentile(valid_values, 1))
        hi = float(np.percentile(valid_values, 99))
        norm = np.clip((metres - lo) / max(1.0, hi - lo), 0.0, 1.0)
        if style == "gray" and raster.mode == "L":
            gray = (norm * 255).astype(np.uint8)
            gray[~valid] = 0
            array[...] = gray
        else:
            array[...] = 0
            _write_ramp_into_rgb(norm, array)
            array[~valid] = (16, 24, 32)
        array.flush()
    finally:
        del array


def _write_ramp_into_rgb(norm: np.ndarray, out: np.memmap) -> None:
    stops = [
        (0.00, (35, 85, 45)),
        (0.35, (92, 139, 63)),
        (0.58, (176, 160, 92)),
        (0.78, (125, 96, 69)),
        (1.00, (238, 238, 228)),
    ]
    for index in range(len(stops) - 1):
        start_pos, start_color = stops[index]
        end_pos, end_color = stops[index + 1]
        mask = (norm >= start_pos) & (norm <= end_pos)
        if not np.any(mask):
            continue
        t = ((norm[mask] - start_pos) / max(0.0001, end_pos - start_pos)).astype(np.float32)
        start = np.array(start_color, dtype=np.float32)
        end = np.array(end_color, dtype=np.float32)
        out[mask] = np.clip(start + (end - start) * t[:, None], 0, 255).astype(np.uint8)
    out[norm <= stops[0][0]] = stops[0][1]
    out[norm >= stops[-1][0]] = stops[-1][1]


def _render_height_sample_raster(values: np.ndarray, raster: DiskRaster) -> None:
    array = _open_disk_raster(raster, write=True)
    try:
        encoded = values.astype(np.int32) + 32768
        encoded[values == HEIGHT_NODATA] = 0
        array[...] = np.clip(encoded, 0, 65535).astype(np.uint16)
        array.flush()
    finally:
        del array


def _render_height_browser_sample_raster(values: np.ndarray, raster: DiskRaster) -> None:
    array = _open_disk_raster(raster, write=True)
    try:
        encoded = values.astype(np.int32) + 32768
        encoded[values == HEIGHT_NODATA] = 0
        clipped = np.clip(encoded, 0, 65535).astype(np.uint16)
        array[...] = 0
        array[:, :, 0] = (clipped & 0xFF).astype(np.uint8)
        array[:, :, 1] = (clipped >> 8).astype(np.uint8)
        array.flush()
    finally:
        del array


def _render_categorical_overlay_raster(
    values: np.ndarray,
    classes: dict,
    *,
    alpha: int,
    transparent_zero: bool,
    raster: DiskRaster,
) -> None:
    array = _open_disk_raster(raster, write=True)
    try:
        array[...] = 0
        height = min(values.shape[0], raster.height)
        width = min(values.shape[1], raster.width)
        clipped = values[:height, :width]
        view = array[:height, :width]
        for raw_id, meta in classes.items():
            class_id = int(raw_id)
            if transparent_zero and class_id == 0:
                continue
            mask = clipped == class_id
            if not np.any(mask):
                continue
            view[mask, :3] = _hex_color(meta.get("color", "#777777"))
            view[mask, 3] = alpha
        array.flush()
    finally:
        del array


def _render_fitted_u8_sample_raster(values: np.ndarray, raster: DiskRaster) -> None:
    array = _open_disk_raster(raster, write=True)
    try:
        array[...] = 0
        height = min(values.shape[0], raster.height)
        width = min(values.shape[1], raster.width)
        array[:height, :width] = values[:height, :width]
        array.flush()
    finally:
        del array


def _render_river_overlay_raster(
    river_mask: np.ndarray,
    width_values: np.ndarray | None,
    width_source: str,
    raster: DiskRaster,
) -> None:
    array = _open_disk_raster(raster, write=True)
    try:
        array[...] = 0
        radii = _river_preview_radii(river_mask, width_values, width_source)
        if np.any(radii > 0):
            alpha = _dilate_river_radii(radii)
            height = min(river_mask.shape[0], raster.height)
            width = min(river_mask.shape[1], raster.width)
            view = array[:height, :width]
            view[:, :, :3] = np.array(PREVIEW_RIVER_COLOR, dtype=np.uint8)
            view[:, :, 3] = alpha[:height, :width]
        array.flush()
    finally:
        del array


def _render_ore_overlay_raster(values: np.ndarray, ore: str, raster: DiskRaster) -> None:
    array = _open_disk_raster(raster, write=True)
    try:
        array[...] = 0
        height = min(values.shape[0], raster.height)
        width = min(values.shape[1], raster.width)
        score = values[:height, :width].astype(np.float32) / 255.0
        view = array[:height, :width]
        view[:, :, :3] = np.array(ORE_COLORS.get(ore, (255, 255, 255)), dtype=np.uint8)
        view[:, :, 3] = np.clip(score * 216, 0, 230).astype(np.uint8)
        array.flush()
    finally:
        del array


def _render_animal_overlay_raster(values: np.ndarray, entity_id: str, raster: DiskRaster) -> None:
    array = _open_disk_raster(raster, write=True)
    try:
        array[...] = 0
        height = min(values.shape[0], raster.height)
        width = min(values.shape[1], raster.width)
        score = values[:height, :width].astype(np.float32) / 255.0
        view = array[:height, :width]
        view[:, :, :3] = np.array(_animal_overlay_color(entity_id), dtype=np.uint8)
        view[:, :, 3] = np.clip(score * 168, 0, 196).astype(np.uint8)
        array.flush()
    finally:
        del array


def _render_cloud_overlay_raster(values: np.ndarray, raster: DiskRaster) -> None:
    array = _open_disk_raster(raster, write=True)
    try:
        array[...] = 0
        height = min(values.shape[0], raster.height)
        width = min(values.shape[1], raster.width)
        coverage = np.clip(values[:height, :width].astype(np.float32) / 100.0, 0.0, 1.0)
        shade = CLOUD_OVERLAY_MAX_SHADE - (CLOUD_OVERLAY_MAX_SHADE - CLOUD_OVERLAY_MIN_SHADE) * coverage
        alpha = np.clip(coverage * CLOUD_OVERLAY_MAX_ALPHA, 0, CLOUD_OVERLAY_MAX_ALPHA).astype(np.uint8)
        view = array[:height, :width]
        gray = np.clip(shade, 0, 255).astype(np.uint8)
        view[:, :, 0] = gray
        view[:, :, 1] = gray
        view[:, :, 2] = gray
        view[:, :, 3] = alpha
        array.flush()
    finally:
        del array


def _render_downfall_overlay_raster(values: np.ndarray, raster: DiskRaster) -> None:
    array = _open_disk_raster(raster, write=True)
    try:
        array[...] = 0
        height = min(values.shape[0], raster.height)
        width = min(values.shape[1], raster.width)
        probability = np.clip(values[:height, :width].astype(np.float32) / DOWNFALL_OVERLAY_MAX_MM, 0.0, 1.0)
        alpha = np.clip(probability * DOWNFALL_OVERLAY_MAX_ALPHA, 0, DOWNFALL_OVERLAY_MAX_ALPHA).astype(np.uint8)
        view = array[:height, :width]
        view[:, :, 0] = DOWNFALL_OVERLAY_COLOR[0]
        view[:, :, 1] = DOWNFALL_OVERLAY_COLOR[1]
        view[:, :, 2] = DOWNFALL_OVERLAY_COLOR[2]
        view[:, :, 3] = alpha
        array.flush()
    finally:
        del array


def _animal_overlay_color(entity_id: str) -> tuple[int, int, int]:
    color = ANIMAL_LAYER_COLORS.get(entity_id)
    if color is not None:
        return color
    seed = sum(ord(ch) * (idx + 1) for idx, ch in enumerate(entity_id))
    return (
        64 + seed % 160,
        64 + (seed * 3) % 160,
        64 + (seed * 7) % 160,
    )


def _animal_label(entity_id: str) -> str:
    label = entity_id.split(":", 1)[-1].replace("_", " ").replace("-", " ").strip()
    return label.title()


def _save_visual_raster_layer(
    root: Path,
    raster: DiskRaster,
    relative_stem: str,
    *,
    tile_size: int = DEFAULT_TILE_SIZE,
    visual_format: str = "png",
    workers: int = 1,
    force: bool = True,
    resampling: Image.Resampling = Image.Resampling.BILINEAR,
    mip_alpha_gamma: float | None = None,
    mip_alpha_cutoff: int = 0,
    mip_alpha_transform_min_factor: int = 2,
    tile_batch_rows: int = DEFAULT_TILE_BATCH_ROWS,
    write_full_images: bool = False,
) -> list[dict[str, Any]]:
    mips: list[dict[str, Any]] = []
    current = raster
    factor = 1
    temp_outputs: list[DiskRaster] = []
    try:
        while True:
            file_path = f"{relative_stem}.{visual_format}" if factor == 1 else f"mips/{factor}/{relative_stem}.{visual_format}"
            if write_full_images:
                _save_disk_raster_image(root / file_path, current, visual_format, force=force)
            tile_template = _save_disk_raster_tiles(
                root,
                current,
                relative_stem,
                factor,
                tile_size=tile_size,
                image_format=visual_format,
                workers=workers,
                force=force,
                tile_batch_rows=tile_batch_rows,
            )
            entry: dict[str, Any] = {
                "factor": factor,
                "width": current.width,
                "height": current.height,
                "tiles": {
                    "size": tile_size,
                    "template": tile_template,
                    "columns": math.ceil(current.width / tile_size),
                    "rows": math.ceil(current.height / tile_size),
                    "format": visual_format,
                },
            }
            if write_full_images:
                entry["file"] = file_path
            mips.append(entry)
            if max(current.width, current.height) <= 512:
                break
            factor *= 2
            next_size = (max(1, math.ceil(raster.width / factor)), max(1, math.ceil(raster.height / factor)))
            next_raster = _resize_disk_raster(
                current,
                root / ".hoverpreview_tmp" / f"{relative_stem.replace('/', '_')}.{factor}.bin",
                next_size,
                resampling,
            )
            if (
                mip_alpha_gamma is not None
                and next_raster.mode == "RGBA"
                and factor >= max(2, mip_alpha_transform_min_factor)
            ):
                _adjust_disk_raster_alpha(next_raster, mip_alpha_gamma, mip_alpha_cutoff)
            temp_outputs.append(next_raster)
            current = next_raster
    finally:
        for temp_raster in temp_outputs:
            _delete_disk_raster(temp_raster)
        temp_root = root / ".hoverpreview_tmp"
        if temp_root.exists():
            shutil.rmtree(temp_root, ignore_errors=True)
    return mips


def _save_sample_raster_tiles(
    root: Path,
    raster: DiskRaster,
    relative_path: str,
    *,
    tile_size: int = DEFAULT_TILE_SIZE,
    encoding: str = "u8",
    workers: int = 1,
    force: bool = True,
    tile_batch_rows: int = DEFAULT_TILE_BATCH_ROWS,
) -> dict[str, Any]:
    path = Path(relative_path)
    if path.parts and path.parts[0] == "samples":
        path = Path(*path.parts[1:])
    stem = str(path.with_suffix(""))
    template = _save_disk_raster_tiles(
        root,
        raster,
        stem,
        1,
        tile_size=tile_size,
        image_format="png",
        workers=workers,
        force=force,
        tile_batch_rows=tile_batch_rows,
        sample_tiles=True,
    )
    return {
        "size": tile_size,
        "template": template,
        "columns": math.ceil(raster.width / tile_size),
        "rows": math.ceil(raster.height / tile_size),
        "encoding": encoding,
        "format": "png",
    }


def _save_disk_raster_tiles(
    root: Path,
    raster: DiskRaster,
    stem: str,
    factor: int,
    *,
    tile_size: int = DEFAULT_TILE_SIZE,
    image_format: str = "png",
    workers: int = 1,
    force: bool = True,
    tile_batch_rows: int = DEFAULT_TILE_BATCH_ROWS,
    sample_tiles: bool = False,
) -> str:
    tile_dir = (root / "sample_tiles" / stem) if sample_tiles else (root / "tiles" / str(factor) / stem)
    tile_dir.mkdir(parents=True, exist_ok=True)
    total_rows = math.ceil(raster.height / tile_size)
    jobs = [(row, min(total_rows, row + tile_batch_rows)) for row in range(0, total_rows, tile_batch_rows)]
    if workers <= 1 or len(jobs) <= 1:
        for row_start, row_end in jobs:
            _write_disk_raster_tile_rows(raster, row_start, row_end, tile_dir, tile_size, image_format, force=force)
    else:
        with ThreadPoolExecutor(max_workers=workers) as executor:
            list(
                executor.map(
                    lambda job: _write_disk_raster_tile_rows(raster, job[0], job[1], tile_dir, tile_size, image_format, force=force),
                    jobs,
                )
            )
    return (f"sample_tiles/{stem}/{{x}}_{{y}}.png" if sample_tiles else f"tiles/{factor}/{stem}/{{x}}_{{y}}.{image_format}")


def _write_disk_raster_tile_rows(
    raster: DiskRaster,
    row_start: int,
    row_end: int,
    tile_dir: Path,
    tile_size: int,
    image_format: str,
    *,
    force: bool,
) -> None:
    array = _open_disk_raster(raster)
    try:
        for tile_z in range(row_start, row_end):
            top = tile_z * tile_size
            bottom = min(raster.height, top + tile_size)
            for tile_x in range(math.ceil(raster.width / tile_size)):
                left = tile_x * tile_size
                right = min(raster.width, left + tile_size)
                path = tile_dir / f"{tile_x}_{tile_z}.{image_format}"
                if path.exists() and not force:
                    continue
                tile = np.asarray(array[top:bottom, left:right])
                _save_image(path, Image.fromarray(tile, mode=raster.mode), image_format, force=force)
    finally:
        del array


def _height_sample_image(values: np.ndarray) -> Image.Image:
    encoded = values.astype(np.int32) + 32768
    encoded[values == HEIGHT_NODATA] = 0
    return Image.fromarray(np.clip(encoded, 0, 65535).astype(np.uint16), mode="I;16")


def _content_bounds(values: np.ndarray, nodata: int) -> dict[str, int]:
    valid = np.argwhere(values != nodata)
    if valid.size == 0:
        return {"left": 0, "top": 0, "right": values.shape[1], "bottom": values.shape[0]}
    top = int(valid[:, 0].min())
    bottom = int(valid[:, 0].max()) + 1
    left = int(valid[:, 1].min())
    right = int(valid[:, 1].max()) + 1
    return {"left": left, "top": top, "right": right, "bottom": bottom}


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
    mode = _hoverpreview_gpu_mode()
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
        if _hoverpreview_gpu_mode() in {"1", "true", "yes", "on", "gpu"}:
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


def _validate_renderer(value: str) -> str:
    renderer = str(value or "auto").strip().lower()
    if renderer in {"canvas", "2d"}:
        return "2d"
    if renderer not in {"auto", "webgl", "2d"}:
        raise ValueError("--renderer must be one of: auto, webgl, 2d")
    return renderer


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
        if layer.get("file"):
            files.append(str(layer["file"]))
        files.extend(str(mip["file"]) for mip in layer.get("mips", []) if mip.get("file"))
        if layer.get("sample_file"):
            files.append(str(layer["sample_file"]))
        if layer.get("browser_sample_file"):
            files.append(str(layer["browser_sample_file"]))
    with (out / ".hoverpreview_cache.json").open("w", encoding="utf-8") as fh:
        json.dump({"generation": generation, "files": sorted(set(files))}, fh, indent=2)
        fh.write("\n")


def _clean_stale_outputs(out: Path, layers: list[dict[str, Any]]) -> None:
    keep_templates: set[str] = set()
    keep_files: set[Path] = {out / HOVER_PREVIEW_INDEX, out / ".hoverpreview_cache.json"}
    for layer in layers:
        if layer.get("file"):
            keep_files.add(out / str(layer["file"]))
        if layer.get("sample_file"):
            keep_files.add(out / str(layer["sample_file"]))
        if layer.get("browser_sample_file"):
            keep_files.add(out / str(layer["browser_sample_file"]))
        for mip in layer.get("mips", []):
            if mip.get("file"):
                keep_files.add(out / str(mip["file"]))
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


def _prune_deploy_minimal_outputs(out: Path) -> None:
    for relative in ("layers", "mips", "samples"):
        path = out / relative
        if path.exists():
            shutil.rmtree(path)


def _fit_image(image: Image.Image, size: tuple[int, int]) -> Image.Image:
    if image.size == size:
        return image
    out = Image.new(image.mode, size, 0)
    out.paste(image.crop((0, 0, min(image.width, size[0]), min(image.height, size[1]))), (0, 0))
    return out


def _resample_visual_to_base_size(
    image: Image.Image,
    size: tuple[int, int],
    *,
    resampling: Image.Resampling,
) -> Image.Image:
    if image.size == size:
        return image
    return image.resize(size, resampling)


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
            if _hoverpreview_gpu_mode() in {"1", "true", "yes", "on", "gpu"}:
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
        ("river_preview_radius", "preview_radius_path" if rivers.get("preview_radius_path") else "preview_radii_path", "preview_radius"),
        ("river_half_width", "half_width_path" if rivers.get("half_width_path") else "river_half_width_path", "half_width"),
        ("river_order", "order_path" if rivers.get("order_path") else "river_order_path", "order"),
    )
    metadata: dict[str, Any] = {
        "source": "river_mask",
        "river_preview_radius_available": False,
        "river_half_width_available": False,
        "river_order_available": False,
        "river_width_scale": PREVIEW_RIVER_WIDTH_SCALE,
        "river_min_radius": PREVIEW_RIVER_MIN_RADIUS,
        "river_max_radius": PREVIEW_RIVER_MAX_RADIUS,
    }
    available_layers: list[tuple[str, dict[str, Any]]] = []
    for source, path_key, prefix in candidates:
        layer_path = rivers.get(path_key)
        if not layer_path:
            continue
        layer = river_u8_layer(rivers, path_key, prefix)
        available = (root / str(layer_path)).exists() or str(layer.get("storage", "tiles")).lower() == "regions"
        if source == "river_half_width":
            metadata["river_half_width_available"] = available
        elif source == "river_preview_radius":
            metadata["river_preview_radius_available"] = available
        elif source == "river_order":
            metadata["river_order_available"] = available
        if not available:
            continue
        available_layers.append((source, layer))
    if available_layers:
        source, layer = available_layers[0]
        values = _read_u8_preview(root, layer, tiles_x, tiles_z, tile_size, scale, missing_ok=True)
        metadata["source"] = source
        metadata["path"] = layer["path"]
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
    if width_source == "river_preview_radius":
        scaled = np.clip(values.astype(np.int16), 0, PREVIEW_RIVER_MAX_RADIUS)
        radii[river] = scaled[river].astype(np.uint8)
        return radii
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
            if _hoverpreview_gpu_mode() in {"1", "true", "yes", "on", "gpu"}:
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
            if _hoverpreview_gpu_mode() in {"1", "true", "yes", "on", "gpu"}:
                raise
    score = values.astype(np.float32) / 255.0
    color = np.array(ORE_COLORS.get(ore, (255, 255, 255)), dtype=np.float32)
    rgba = np.zeros((*values.shape, 4), dtype=np.uint8)
    rgba[:, :, :3] = color.astype(np.uint8)
    rgba[:, :, 3] = np.clip(score * 216, 0, 230).astype(np.uint8)
    return Image.fromarray(rgba, mode="RGBA")
