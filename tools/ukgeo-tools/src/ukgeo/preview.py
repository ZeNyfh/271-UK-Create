from __future__ import annotations

from pathlib import Path
import math

import numpy as np
from PIL import Image, ImageDraw

from .manifest import read_manifest
from .tiles import HEIGHT_NODATA, read_r16_tile, read_u8_tile

def u8_layer_grid(manifest: dict, layer: str) -> tuple[int, int, int, int, int]:
    """Return tiles_x, tiles_z, data_width, data_depth, cell_blocks for a u8 tile layer."""
    tile_size = int(manifest["tile_size"])
    padded_width = int(manifest["world"]["padded_width"])
    padded_depth = int(manifest["world"]["padded_depth"])
    if layer == "vegetation":
        vegetation = manifest["vegetation"]
        cell_blocks = max(1, int(vegetation.get("cell_blocks", 1)))
        data_width = int(vegetation.get("width_cells", math.ceil(int(manifest["world"]["width"]) / cell_blocks)))
        data_depth = int(vegetation.get("depth_cells", math.ceil(int(manifest["world"]["depth"]) / cell_blocks)))
        tiles_x = math.ceil(padded_width / (tile_size * cell_blocks))
        tiles_z = math.ceil(padded_depth / (tile_size * cell_blocks))
        return tiles_x, tiles_z, data_width, data_depth, cell_blocks
    tiles_x = math.ceil(padded_width / tile_size)
    tiles_z = math.ceil(padded_depth / tile_size)
    return tiles_x, tiles_z, padded_width, padded_depth, 1


def upscale_cells_to_blocks(cells: np.ndarray, cell_blocks: int) -> np.ndarray:
    if cell_blocks <= 1:
        return cells
    return np.repeat(np.repeat(cells, cell_blocks, axis=0), cell_blocks, axis=1)


def read_vegetation_preview(root: Path, manifest: dict, scale: int, *, missing_ok: bool = False) -> np.ndarray:
    vegetation = manifest["vegetation"]
    tiles_x, tiles_z, width_cells, depth_cells, cell_blocks = u8_layer_grid(manifest, "vegetation")
    values = _read_u8_preview(
        root,
        vegetation["path"],
        tiles_x,
        tiles_z,
        int(manifest["tile_size"]),
        scale,
        missing_ok=missing_ok,
        data_width=width_cells,
        data_depth=depth_cells,
    )
    if cell_blocks > 1:
        values = upscale_cells_to_blocks(values, cell_blocks)
    return values


ORE_COLORS: dict[str, tuple[int, int, int]] = {
    "coal": (40, 40, 40),
    "iron": (210, 70, 45),
    "copper": (230, 130, 45),
    "zinc": (70, 145, 230),
    "tin": (170, 95, 220),
    "gold": (245, 205, 65),
    "andesite": (122, 126, 123),
    "diorite": (205, 205, 198),
    "granite": (176, 126, 106),
    "ochrum": (212, 171, 70),
    "calcite": (236, 232, 210),
    "scoria": (78, 60, 54),
    "tuff": (104, 112, 98),
    "crimsite": (156, 55, 57),
    "limestone": (190, 184, 150),
    "asurine": (65, 130, 175),
    "veridium": (75, 145, 95),
    "smooth_basalt": (56, 60, 66),
}


def make_preview(root: Path, layer: str, out: Path, max_size: int = 4096, style: str = "auto", legend_scale: int = 20) -> None:
    manifest = read_manifest(root / "manifest.json")
    tile_size = manifest["tile_size"]
    tiles_x = math.ceil(manifest["world"]["padded_width"] / tile_size)
    tiles_z = math.ceil(manifest["world"]["padded_depth"] / tile_size)
    full_width = tiles_x * tile_size
    full_depth = tiles_z * tile_size
    if max_size <= 0:
        scale = 1
    else:
        scale = max(1, math.ceil(max(full_width, full_depth) / max_size))
    if layer == "height":
        output = _height_image(_read_height_preview(root, manifest, tiles_x, tiles_z, tile_size, scale), style)
    elif layer == "surface":
        if "surface_geology" not in manifest:
            raise FileNotFoundError("No surface geology layer is present. Run ukgeo make-surface-geology-tiles first.")
        values = _read_u8_preview(root, manifest["surface_geology"]["path"], tiles_x, tiles_z, tile_size, scale, missing_ok=False)
        output = _surface_image(values, manifest["surface_geology"].get("classes", {}), legend_scale)
    elif layer == "rivers":
        if "rivers" not in manifest:
            raise FileNotFoundError("No river layer is present. Run ukgeo make-river-tiles first.")
        values = _read_u8_preview(root, manifest["rivers"]["path"], tiles_x, tiles_z, tile_size, scale, missing_ok=False)
        height = _read_height_preview(root, manifest, tiles_x, tiles_z, tile_size, scale)
        output = _single_mask_overlay(height, values, "rivers", (65, 145, 230), legend_scale)
    elif layer == "vegetation":
        if "vegetation" not in manifest:
            raise FileNotFoundError("No vegetation layer is present. Run ukgeo make-vegetation-tiles first.")
        values = read_vegetation_preview(root, manifest, scale, missing_ok=False)
        output = _surface_image(values, manifest["vegetation"].get("classes", {}), legend_scale)
    elif layer in {"ores", "ore:all"}:
        height = _read_height_preview(root, manifest, tiles_x, tiles_z, tile_size, scale)
        output = _all_ores_image(root, manifest, tiles_x, tiles_z, tile_size, scale, height, legend_scale)
    elif layer.startswith("ore:"):
        ore = layer.split(":", 1)[1]
        if ore not in manifest.get("ore_layers", {}):
            raise ValueError(f"Unknown ore layer {ore!r}")
        values = _read_u8_preview(root, manifest["ore_layers"][ore]["path"], tiles_x, tiles_z, tile_size, scale, missing_ok=False)
        if style == "overlay":
            height = _read_height_preview(root, manifest, tiles_x, tiles_z, tile_size, scale)
            output = _single_ore_overlay(height, values, ore, legend_scale)
        else:
            output = _ore_image(values, style)
    else:
        raise ValueError("layer must be height, surface, vegetation, rivers, ores, ore:all, or ore:<name>")
    out.parent.mkdir(parents=True, exist_ok=True)
    output.save(out)


def _read_height_preview(root: Path, manifest: dict, tiles_x: int, tiles_z: int, tile_size: int, scale: int) -> np.ndarray:
    base = root / manifest["height"]["path"]
    image = np.full((math.ceil(tiles_z * tile_size / scale), math.ceil(tiles_x * tile_size / scale)), HEIGHT_NODATA, dtype=np.int16)
    for tz in range(tiles_z):
        for tx in range(tiles_x):
            arr = read_r16_tile(base / f"{tx:03d}_{tz:03d}.r16.gz", tile_size)
            y0, y1, x0, x1, local_y, local_x = _tile_preview_window(tx, tz, tile_size, scale)
            image[y0:y1, x0:x1] = arr[np.ix_(local_y, local_x)]
    return image


def _read_u8_preview(
    root: Path,
    layer_path: str,
    tiles_x: int,
    tiles_z: int,
    tile_size: int,
    scale: int,
    missing_ok: bool,
    *,
    data_width: int | None = None,
    data_depth: int | None = None,
) -> np.ndarray:
    base = root / layer_path
    width = data_width if data_width is not None else tiles_x * tile_size
    depth = data_depth if data_depth is not None else tiles_z * tile_size
    if not base.exists():
        if missing_ok:
            return np.zeros((math.ceil(depth / scale), math.ceil(width / scale)), dtype=np.uint8)
        raise FileNotFoundError(f"Tile directory is missing: {base}")
    image = np.zeros((math.ceil(depth / scale), math.ceil(width / scale)), dtype=np.uint8)
    for tz in range(tiles_z):
        for tx in range(tiles_x):
            tile_path = base / f"{tx:03d}_{tz:03d}.u8.gz"
            if not tile_path.exists():
                if missing_ok:
                    continue
                raise FileNotFoundError(f"Tile is missing: {tile_path}")
            arr = read_u8_tile(tile_path, tile_size)
            if scale > 1:
                non_zero = np.argwhere(arr > 0)
                if non_zero.size > 0:
                    gz = non_zero[:, 0] + tz * tile_size
                    gx = non_zero[:, 1] + tx * tile_size
                    pz = gz // scale
                    px = gx // scale
                    pz = np.clip(pz, 0, image.shape[0] - 1)
                    px = np.clip(px, 0, image.shape[1] - 1)
                    np.maximum.at(image, (pz, px), arr[non_zero[:, 0], non_zero[:, 1]])
            else:
                y0, y1, x0, x1, local_y, local_x = _tile_preview_window(tx, tz, tile_size, scale)
                image[y0:y1, x0:x1] = arr[np.ix_(local_y, local_x)]
    return image


def _tile_preview_window(tile_x: int, tile_z: int, tile_size: int, scale: int) -> tuple[int, int, int, int, np.ndarray, np.ndarray]:
    tile_min_x = tile_x * tile_size
    tile_min_y = tile_z * tile_size
    tile_max_x = tile_min_x + tile_size
    tile_max_y = tile_min_y + tile_size
    out_x0 = math.ceil(tile_min_x / scale)
    out_y0 = math.ceil(tile_min_y / scale)
    out_x1 = math.ceil(tile_max_x / scale)
    out_y1 = math.ceil(tile_max_y / scale)
    local_x = np.arange(out_x0, out_x1, dtype=np.int32) * scale - tile_min_x
    local_y = np.arange(out_y0, out_y1, dtype=np.int32) * scale - tile_min_y
    return out_y0, out_y1, out_x0, out_x1, local_y, local_x


def _height_image(values: np.ndarray, style: str) -> Image.Image:
    valid = values != HEIGHT_NODATA
    if not valid.any():
        return Image.fromarray(np.zeros(values.shape, dtype=np.uint8), mode="L")
    metres = values.astype(np.float32) * 0.1
    valid_values = metres[valid]
    lo = float(np.percentile(valid_values, 1))
    hi = float(np.percentile(valid_values, 99))
    norm = np.clip((metres - lo) / max(1.0, hi - lo), 0.0, 1.0)
    if style == "gray":
        gray = (norm * 255).astype(np.uint8)
        gray[~valid] = 0
        return Image.fromarray(gray, mode="L")
    rgb = _apply_ramp(
        norm,
        [
            (0.00, (35, 85, 45)),
            (0.35, (92, 139, 63)),
            (0.58, (176, 160, 92)),
            (0.78, (125, 96, 69)),
            (1.00, (238, 238, 228)),
        ],
    )
    rgb[~valid] = (16, 24, 32)
    return Image.fromarray(rgb, mode="RGB")


def _all_ores_image(root: Path, manifest: dict, tiles_x: int, tiles_z: int, tile_size: int, scale: int, height: np.ndarray, legend_scale: int) -> Image.Image:
    background = np.asarray(_height_image(height, "auto"), dtype=np.float32)
    weighted_color = np.zeros_like(background, dtype=np.float32)
    weights = np.zeros(height.shape, dtype=np.float32)
    present: list[tuple[str, tuple[int, int, int], int]] = []
    missing_layers: list[str] = []
    for ore, layer in manifest.get("ore_layers", {}).items():
        layer_root = root / layer["path"]
        if not layer_root.exists():
            missing_layers.append(ore)
            continue
        values = _read_u8_preview(root, layer["path"], tiles_x, tiles_z, tile_size, scale, missing_ok=True)
        score = values.astype(np.float32) / 255.0
        color = np.array(ORE_COLORS.get(ore, (255, 255, 255)), dtype=np.float32)
        weighted_color += score[:, :, None] * color
        weights += score
        max_score = int(values.max()) if values.size else 0
        if max_score > 0:
            present.append((ore, tuple(int(c) for c in color), max_score))
    if not present and missing_layers:
        raise FileNotFoundError(
            "No ore tile data is present. Run ukgeo make-ore-tiles before previewing --layer ores."
        )
    ore_rgb = np.divide(weighted_color, weights[:, :, None], out=np.zeros_like(weighted_color), where=weights[:, :, None] > 0)
    alpha = np.clip(weights * 0.85, 0.0, 0.9)[:, :, None]
    output = background * (1.0 - alpha) + ore_rgb * alpha
    image = Image.fromarray(np.clip(output, 0, 255).astype(np.uint8), mode="RGB")
    return _add_ore_legend(
        image,
        present or [(ore, ORE_COLORS.get(ore, (255, 255, 255)), 0) for ore in manifest.get("ore_layers", {})],
        legend_scale,
    )


def _single_ore_overlay(height: np.ndarray, values: np.ndarray, ore: str, legend_scale: int) -> Image.Image:
    background = np.asarray(_height_image(height, "auto"), dtype=np.float32)
    score = values.astype(np.float32) / 255.0
    color = np.array(ORE_COLORS.get(ore, (255, 255, 255)), dtype=np.float32)
    alpha = np.clip(score * 0.9, 0.0, 0.9)[:, :, None]
    output = background * (1.0 - alpha) + color * alpha
    image = Image.fromarray(np.clip(output, 0, 255).astype(np.uint8), mode="RGB")
    return _add_ore_legend(image, [(ore, tuple(int(c) for c in color), int(values.max()) if values.size else 0)], legend_scale)


def _single_mask_overlay(height: np.ndarray, values: np.ndarray, name: str, color_value: tuple[int, int, int], legend_scale: int) -> Image.Image:
    background = np.asarray(_height_image(height, "auto"), dtype=np.float32)
    score = values.astype(np.float32) / 255.0
    color = np.array(color_value, dtype=np.float32)
    alpha = np.clip(score * 0.95, 0.0, 0.95)[:, :, None]
    output = background * (1.0 - alpha) + color * alpha
    image = Image.fromarray(np.clip(output, 0, 255).astype(np.uint8), mode="RGB")
    return _add_ore_legend(image, [(name, color_value, int(values.max()) if values.size else 0)], legend_scale)


def _surface_image(values: np.ndarray, classes: dict, legend_scale: int) -> Image.Image:
    rgb = np.zeros((*values.shape, 3), dtype=np.uint8)
    legend: list[tuple[str, tuple[int, int, int], int]] = []
    for raw_id, meta in sorted(classes.items(), key=lambda item: int(item[0])):
        class_id = int(raw_id)
        color = _hex_color(meta.get("color", "#777777"))
        rgb[values == class_id] = color
        if np.any(values == class_id):
            legend.append((meta.get("name", raw_id), color, class_id))
    return _add_ore_legend(Image.fromarray(rgb, mode="RGB"), legend, legend_scale)


def _hex_color(value: str) -> tuple[int, int, int]:
    text = str(value).strip().lstrip("#")
    if len(text) != 6:
        return (119, 119, 119)
    return (int(text[0:2], 16), int(text[2:4], 16), int(text[4:6], 16))


def _add_ore_legend(image: Image.Image, ores: list[tuple[str, tuple[int, int, int], int]], legend_scale: int) -> Image.Image:
    scale = max(1, legend_scale)
    legend_width = 190 * scale
    row_height = 28 * scale
    padding = 12 * scale
    swatch = 18 * scale
    legend_height = padding * 2 + row_height * len(ores)
    out = Image.new("RGB", (image.width + legend_width, max(image.height, legend_height)), (16, 24, 32))
    out.paste(image, (0, 0))
    draw = ImageDraw.Draw(out)
    x = image.width + padding
    y = padding
    for name, color, max_score in ores:
        draw.rectangle((x, y + 4 * scale, x + swatch, y + 4 * scale + swatch), fill=color)
        draw.text((x + 28 * scale, y + 4 * scale), f"{name} max {max_score}", fill=(235, 238, 230), font_size=14 * scale)
        y += row_height
    return out


def _ore_image(values: np.ndarray, style: str) -> Image.Image:
    if style == "gray":
        return Image.fromarray(values, mode="L")
    norm = values.astype(np.float32) / 255.0
    rgb = _apply_ramp(
        norm,
        [
            (0.00, (0, 0, 0)),
            (0.20, (38, 39, 67)),
            (0.45, (91, 64, 136)),
            (0.72, (210, 110, 55)),
            (1.00, (255, 230, 120)),
        ],
    )
    return Image.fromarray(rgb, mode="RGB")


def _apply_ramp(norm: np.ndarray, stops: list[tuple[float, tuple[int, int, int]]]) -> np.ndarray:
    rgb = np.zeros((*norm.shape, 3), dtype=np.float32)
    for index in range(len(stops) - 1):
        start_pos, start_color = stops[index]
        end_pos, end_color = stops[index + 1]
        mask = (norm >= start_pos) & (norm <= end_pos)
        t = (norm[mask] - start_pos) / max(0.0001, end_pos - start_pos)
        start = np.array(start_color, dtype=np.float32)
        end = np.array(end_color, dtype=np.float32)
        rgb[mask] = start + (end - start) * t[:, None]
    rgb[norm <= stops[0][0]] = stops[0][1]
    rgb[norm >= stops[-1][0]] = stops[-1][1]
    return np.clip(rgb, 0, 255).astype(np.uint8)
