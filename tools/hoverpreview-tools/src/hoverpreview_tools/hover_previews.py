from __future__ import annotations

from pathlib import Path
import gc
import json
import math
import shutil
from typing import Any

import numpy as np
from PIL import Image

from ukgeo.manifest import read_manifest
from ukgeo.preview import ORE_COLORS, _height_image, _hex_color, _read_height_preview, _read_u8_preview, read_vegetation_preview
from ukgeo.tiles import HEIGHT_NODATA


HOVER_PREVIEW_FORMAT = "ukgeo-hoverpreviews-v1"
HOVER_PREVIEW_INDEX = "hover_manifest.json"


def hover_preview_scale(manifest: dict[str, Any], max_size: int) -> tuple[int, int, int]:
    tile_size = int(manifest["tile_size"])
    tiles_x = math.ceil(int(manifest["world"]["padded_width"]) / tile_size)
    tiles_z = math.ceil(int(manifest["world"]["padded_depth"]) / tile_size)
    full_width = tiles_x * tile_size
    full_depth = tiles_z * tile_size
    scale = 1 if max_size <= 0 else max(1, math.ceil(max(full_width, full_depth) / max_size))
    return scale, tiles_x, tiles_z


def export_hover_previews(root: Path, out: Path, max_size: int = 4096, style: str = "auto", clean: bool = False) -> Path:
    manifest = read_manifest(root / "manifest.json")
    tile_size = int(manifest["tile_size"])
    scale, tiles_x, tiles_z = hover_preview_scale(manifest, max_size)

    if clean and out.exists():
        shutil.rmtree(out)
    (out / "layers").mkdir(parents=True, exist_ok=True)
    (out / "samples").mkdir(parents=True, exist_ok=True)
    (out / "mips").mkdir(parents=True, exist_ok=True)

    height_values = _read_height_preview(root, manifest, tiles_x, tiles_z, tile_size, scale)
    base_size = (height_values.shape[1], height_values.shape[0])
    height_mips = _save_visual_layer(out, _height_image(height_values, style).convert("RGB"), "layers/height.png")
    _height_sample_image(height_values).save(out / "samples" / "height_u16.png")
    del height_values
    gc.collect()

    layers: list[dict[str, Any]] = [
        {"name": "height", "kind": "base", "file": "layers/height.png", "mips": height_mips, "sample_file": "samples/height_u16.png"},
    ]

    if "surface_geology" in manifest and (root / manifest["surface_geology"]["path"]).exists():
        values = _read_u8_preview(root, manifest["surface_geology"]["path"], tiles_x, tiles_z, tile_size, scale, missing_ok=False)
        visual = _fit_image(_categorical_overlay_image(values, manifest["surface_geology"].get("classes", {}), alpha=166, transparent_zero=False), base_size)
        sample = _fit_image(Image.fromarray(values, mode="L"), base_size)
        mips = _save_visual_layer(out, visual, "layers/surface.png")
        sample.save(out / "samples" / "surface_u8.png")
        layers.append({"name": "surface", "kind": "overlay", "file": "layers/surface.png", "mips": mips, "sample_file": "samples/surface_u8.png"})
        del values, visual, sample
        gc.collect()

    if "vegetation" in manifest and (root / manifest["vegetation"]["path"]).exists():
        values = read_vegetation_preview(root, manifest, scale, missing_ok=False)
        visual = _fit_image(_categorical_overlay_image(values, manifest["vegetation"].get("classes", {}), alpha=176, transparent_zero=True), base_size)
        sample = _fit_image(Image.fromarray(values, mode="L"), base_size)
        mips = _save_visual_layer(out, visual, "layers/vegetation.png")
        sample.save(out / "samples" / "vegetation_u8.png")
        layers.append({"name": "vegetation", "kind": "overlay", "file": "layers/vegetation.png", "mips": mips, "sample_file": "samples/vegetation_u8.png"})
        del values, visual, sample
        gc.collect()

    if "rivers" in manifest and (root / manifest["rivers"]["path"]).exists():
        values = _read_u8_preview(root, manifest["rivers"]["path"], tiles_x, tiles_z, tile_size, scale, missing_ok=False)
        visual = _fit_image(_mask_overlay_image(values, (65, 145, 230)), base_size)
        sample = _fit_image(Image.fromarray(values, mode="L"), base_size)
        mips = _save_visual_layer(out, visual, "layers/rivers.png")
        sample.save(out / "samples" / "rivers_u8.png")
        layers.append({"name": "rivers", "kind": "overlay", "file": "layers/rivers.png", "mips": mips, "sample_file": "samples/rivers_u8.png"})
        del values, visual, sample
        gc.collect()

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
        values = _read_u8_preview(root, layer["path"], tiles_x, tiles_z, tile_size, scale, missing_ok=True)
        visual = _fit_image(_ore_overlay_image(values, ore), base_size)
        sample = _fit_image(Image.fromarray(values, mode="L"), base_size)
        sample_path = ore_sample_dir / f"{ore}_u8.png"
        mips = _save_visual_layer(out, visual, f"layers/ores/{ore}.png")
        sample.save(sample_path)
        ore_layers.append(
            {
                "name": f"ore:{ore}",
                "ore": ore,
                "kind": "ore",
                "file": f"layers/ores/{ore}.png",
                "mips": mips,
                "sample_file": f"samples/ores/{ore}_u8.png",
            }
        )
        del values, visual, sample
        gc.collect()
    layers.extend(ore_layers)

    index = {
        "format": HOVER_PREVIEW_FORMAT,
        "scale": scale,
        "max_size": max_size,
        "style": style,
        "tile_size": tile_size,
        "image_width": base_size[0],
        "image_height": base_size[1],
        "world": manifest["world"],
        "georeferencing": manifest.get("georeferencing", {}),
        "minecraft_origin": _minecraft_origin(manifest),
        "surface_geology": manifest.get("surface_geology", {}),
        "vegetation": manifest.get("vegetation", {}),
        "layers": layers,
    }
    with (out / HOVER_PREVIEW_INDEX).open("w", encoding="utf-8") as fh:
        json.dump(index, fh, indent=2)
        fh.write("\n")
    return out


def _height_sample_image(values: np.ndarray) -> Image.Image:
    encoded = values.astype(np.int32) + 32768
    encoded[values == HEIGHT_NODATA] = 0
    return Image.fromarray(np.clip(encoded, 0, 65535).astype(np.uint16), mode="I;16")


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


def _save_visual_layer(root: Path, image: Image.Image, relative_path: str) -> list[dict[str, Any]]:
    path = root / relative_path
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path)
    mips: list[dict[str, Any]] = [{"factor": 1, "file": relative_path, "width": image.width, "height": image.height}]
    factor = 2
    current = image
    while max(current.size) > 512:
        size = (max(1, math.ceil(image.width / factor)), max(1, math.ceil(image.height / factor)))
        current = image.resize(size, Image.Resampling.BILINEAR)
        mip_path = root / "mips" / str(factor) / relative_path
        mip_path.parent.mkdir(parents=True, exist_ok=True)
        current.save(mip_path)
        mips.append({"factor": factor, "file": f"mips/{factor}/{relative_path}", "width": current.width, "height": current.height})
        factor *= 2
    return mips


def _fit_image(image: Image.Image, size: tuple[int, int]) -> Image.Image:
    if image.size == size:
        return image
    out = Image.new(image.mode, size, 0)
    out.paste(image.crop((0, 0, min(image.width, size[0]), min(image.height, size[1]))), (0, 0))
    return out


def _categorical_overlay_image(values: np.ndarray, classes: dict, *, alpha: int, transparent_zero: bool) -> Image.Image:
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


def _mask_overlay_image(values: np.ndarray, color_value: tuple[int, int, int]) -> Image.Image:
    score = values.astype(np.float32) / 255.0
    rgba = np.zeros((*values.shape, 4), dtype=np.uint8)
    rgba[:, :, :3] = np.array(color_value, dtype=np.uint8)
    rgba[:, :, 3] = np.clip(score * 240, 0, 240).astype(np.uint8)
    return Image.fromarray(rgba, mode="RGBA")


def _ore_overlay_image(values: np.ndarray, ore: str) -> Image.Image:
    score = values.astype(np.float32) / 255.0
    color = np.array(ORE_COLORS.get(ore, (255, 255, 255)), dtype=np.float32)
    rgba = np.zeros((*values.shape, 4), dtype=np.uint8)
    rgba[:, :, :3] = color.astype(np.uint8)
    rgba[:, :, 3] = np.clip(score * 216, 0, 230).astype(np.uint8)
    return Image.fromarray(rgba, mode="RGBA")
