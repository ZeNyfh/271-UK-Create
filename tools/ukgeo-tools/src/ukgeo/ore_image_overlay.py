from __future__ import annotations

from pathlib import Path
import math

import numpy as np
from PIL import Image
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

    manifest.setdefault("ore_layers", {}).setdefault(ore, default_u8_layer(f"ores/{ore}"))
    layer = manifest["ore_layers"][ore]
    layer_root = out / layer["path"]
    layer_root.mkdir(parents=True, exist_ok=True)
    score = max(0, min(255, int(score)))

    changed_tiles = 0
    for tile_z in tqdm(range(tiles_z), desc=f"{ore} image overlay rows"):
        z = tile_z * tile_size + np.arange(tile_size)
        valid_z = z < depth
        image_y = np.clip(((z + 0.5) * red_mask.shape[0] / depth).astype(np.int64), 0, red_mask.shape[0] - 1)
        for tile_x in range(tiles_x):
            x = tile_x * tile_size + np.arange(tile_size)
            valid_x = x < width
            if not np.any(valid_x) or not np.any(valid_z):
                continue
            image_x = np.clip(((x + 0.5) * red_mask.shape[1] / width).astype(np.int64), 0, red_mask.shape[1] - 1)
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
        "note": "Image red pixels were max-merged into the existing ore score tiles over the manifest GB extent.",
    }
    overlays = [item for item in overlays if not (item.get("ore") == ore and item.get("source") == str(image))]
    overlays.append(entry)
    manifest["ore_image_overlays"] = overlays
    write_manifest(manifest_path, manifest)
    console.print(f"{ore}: applied image overlay from {image} to {changed_tiles} tiles")


def _read_red_mask(path: Path, *, red_min: int, green_max: int, blue_max: int) -> np.ndarray:
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
