from __future__ import annotations

from pathlib import Path
import math
import re
import xml.etree.ElementTree as ET

import numpy as np
from PIL import Image, ImageDraw
from rich.console import Console
from tqdm import tqdm

from .manifest import default_u8_layer, read_manifest, write_manifest
from .tiles import read_u8_tile, write_u8_tile

console = Console()


def apply_ore_image_overlay(
    *,
    image: Path,
    manifest_path: Path,
    out: Path,
    ore: str = "iron",
    score: int = 255,
    red_min: int = 180,
    green_max: int = 120,
    blue_max: int = 120,
    fit: str = "cover",
) -> None:
    manifest = read_manifest(manifest_path)
    tile_size = int(manifest["tile_size"])
    world = manifest["world"]
    width = int(world["width"])
    depth = int(world["depth"])
    padded_width = int(world["padded_width"])
    padded_depth = int(world["padded_depth"])
    tiles_x = math.ceil(padded_width / tile_size)
    tiles_z = math.ceil(padded_depth / tile_size)

    red_mask = _read_red_mask(image, red_min=red_min, green_max=green_max, blue_max=blue_max)
    if not np.any(red_mask):
        console.print(f"[yellow]No red ore pixels found in {image}; {ore} tiles were unchanged.[/yellow]")
        return
    scale, offset_x, offset_z = _overlay_placement(red_mask.shape[1], red_mask.shape[0], width, depth, fit)

    manifest.setdefault("ore_layers", {}).setdefault(ore, default_u8_layer(f"ores/{ore}"))
    layer = manifest["ore_layers"][ore]
    layer_root = out / layer["path"]
    layer_root.mkdir(parents=True, exist_ok=True)
    score = max(0, min(255, int(score)))

    changed_tiles = 0
    for tile_z in tqdm(range(tiles_z), desc=f"{ore} image overlay rows"):
        z = tile_z * tile_size + np.arange(tile_size)
        valid_z = z < depth
        image_y_float = (z + 0.5 - offset_z) / scale
        valid_z &= (image_y_float >= 0) & (image_y_float < red_mask.shape[0])
        image_y = np.clip(image_y_float.astype(np.int64), 0, red_mask.shape[0] - 1)
        for tile_x in range(tiles_x):
            x = tile_x * tile_size + np.arange(tile_size)
            valid_x = x < width
            if not np.any(valid_x) or not np.any(valid_z):
                continue
            image_x_float = (x + 0.5 - offset_x) / scale
            valid_x &= (image_x_float >= 0) & (image_x_float < red_mask.shape[1])
            if not np.any(valid_x):
                continue
            image_x = np.clip(image_x_float.astype(np.int64), 0, red_mask.shape[1] - 1)
            mask = red_mask[np.ix_(image_y, image_x)]
            mask &= valid_z[:, None]
            mask &= valid_x[None, :]
            if not np.any(mask):
                continue

            path = layer_root / f"{tile_x:03d}_{tile_z:03d}.u8.gz"
            if path.exists():
                tile = read_u8_tile(path, tile_size).copy()
            else:
                tile = np.zeros((tile_size, tile_size), dtype=np.uint8)
            before = tile.copy()
            tile[mask] = np.maximum(tile[mask], score)
            if not np.array_equal(tile, before):
                write_u8_tile(path, tile, tile_size)
                changed_tiles += 1

    overlays = manifest.setdefault("ore_image_overlays", [])
    entry = {
        "ore": ore,
        "source": str(image),
        "score": score,
        "red_min": int(red_min),
        "green_max": int(green_max),
        "blue_max": int(blue_max),
        "fit": fit,
        "note": "Image red pixels were aspect-preserving max-merged into the existing ore score tiles over the manifest GB extent.",
    }
    overlays = [item for item in overlays if not (item.get("ore") == ore and item.get("source") == str(image))]
    overlays.append(entry)
    manifest["ore_image_overlays"] = overlays
    write_manifest(manifest_path, manifest)
    console.print(f"{ore}: applied image overlay from {image} to {changed_tiles} tiles")


def _read_red_mask(path: Path, *, red_min: int, green_max: int, blue_max: int) -> np.ndarray:
    if path.suffix.lower() == ".svg":
        return _read_svg_red_mask(path, red_min=red_min, green_max=green_max, blue_max=blue_max)
    pixels = np.asarray(Image.open(path).convert("RGBA"), dtype=np.uint8)
    red = pixels[:, :, 0].astype(np.uint16)
    green = pixels[:, :, 1].astype(np.uint16)
    blue = pixels[:, :, 2].astype(np.uint16)
    alpha = pixels[:, :, 3]
    return (
        (alpha > 0)
        & (red >= int(red_min))
        & (green <= int(green_max))
        & (blue <= int(blue_max))
        & (red > green * 3 // 2)
        & (red > blue * 3 // 2)
    )


def _read_svg_red_mask(path: Path, *, red_min: int, green_max: int, blue_max: int) -> np.ndarray:
    root = ET.parse(path).getroot()
    min_x, min_y, width, height = _svg_viewbox(root)
    mask = Image.new("L", (max(1, math.ceil(width)), max(1, math.ceil(height))), 0)
    draw = ImageDraw.Draw(mask)
    for element in root.iter():
        if not element.tag.endswith("path"):
            continue
        fill = _svg_fill(element)
        if not _is_red_fill(fill, red_min=red_min, green_max=green_max, blue_max=blue_max):
            continue
        d = element.attrib.get("d", "")
        for polygon in _path_polygons(d):
            if len(polygon) >= 3:
                draw.polygon([(x - min_x, y - min_y) for x, y in polygon], fill=255)
    return np.asarray(mask, dtype=np.uint8) > 0


def _svg_viewbox(root: ET.Element) -> tuple[float, float, float, float]:
    raw = root.attrib.get("viewBox")
    if raw:
        values = [float(v) for v in re.split(r"[\s,]+", raw.strip()) if v]
        if len(values) == 4:
            return values[0], values[1], values[2], values[3]
    return 0.0, 0.0, _svg_length(root.attrib.get("width", "1")), _svg_length(root.attrib.get("height", "1"))


def _svg_length(raw: str) -> float:
    match = re.match(r"\s*(-?\d+(?:\.\d+)?)", raw)
    return float(match.group(1)) if match else 1.0


def _svg_fill(element: ET.Element) -> str:
    if "fill" in element.attrib:
        return element.attrib["fill"]
    style = element.attrib.get("style", "")
    for item in style.split(";"):
        key, _, value = item.partition(":")
        if key.strip() == "fill":
            return value.strip()
    return ""


def _is_red_fill(fill: str, *, red_min: int, green_max: int, blue_max: int) -> bool:
    fill = fill.strip()
    if not fill.startswith("#"):
        return False
    value = fill.removeprefix("#")
    if len(value) == 3:
        value = "".join(c * 2 for c in value)
    if len(value) != 6:
        return False
    red = int(value[0:2], 16)
    green = int(value[2:4], 16)
    blue = int(value[4:6], 16)
    return red >= red_min and green <= green_max and blue <= blue_max and red > green * 3 // 2 and red > blue * 3 // 2


def _path_polygons(d: str) -> list[list[tuple[float, float]]]:
    tokens = re.findall(r"[MmLlZz]|-?\d+(?:\.\d+)?", d)
    polygons: list[list[tuple[float, float]]] = []
    current: list[tuple[float, float]] = []
    command = ""
    i = 0
    x = 0.0
    y = 0.0
    while i < len(tokens):
        token = tokens[i]
        if re.fullmatch(r"[MmLlZz]", token):
            command = token
            i += 1
            if command in {"Z", "z"}:
                if current:
                    polygons.append(current)
                    current = []
                continue
        if command in {"M", "L", "m", "l"} and i + 1 < len(tokens):
            nx = float(tokens[i])
            ny = float(tokens[i + 1])
            i += 2
            if command.islower():
                nx += x
                ny += y
            x, y = nx, ny
            if command in {"M", "m"}:
                if current:
                    polygons.append(current)
                current = [(x, y)]
                command = "l" if command == "m" else "L"
            else:
                current.append((x, y))
        else:
            i += 1
    if current:
        polygons.append(current)
    return polygons


def _overlay_placement(source_width: int, source_height: int, target_width: int, target_depth: int, fit: str) -> tuple[float, float, float]:
    if fit == "contain":
        scale = min(target_width / source_width, target_depth / source_height)
    elif fit == "cover":
        scale = max(target_width / source_width, target_depth / source_height)
    else:
        raise ValueError("fit must be one of: cover, contain")
    placed_width = source_width * scale
    placed_height = source_height * scale
    return scale, (target_width - placed_width) / 2.0, (target_depth - placed_height) / 2.0
