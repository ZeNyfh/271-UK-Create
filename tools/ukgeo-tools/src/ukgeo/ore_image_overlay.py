from __future__ import annotations

from pathlib import Path
from collections.abc import Mapping
import math
import re
import xml.etree.ElementTree as ET

import numpy as np
from PIL import Image, ImageDraw
from rich.console import Console
from tqdm import tqdm

from .manifest import default_u8_layer, read_manifest, write_manifest
from .tiles import HEIGHT_NODATA, read_r16_tile, read_u8_tile, write_u8_tile, u8_extension, r16_extension

console = Console()

BBox = tuple[float, float, float, float]


def apply_ore_image_overlay(
    *,
    image: Path,
    manifest_path: Path,
    out: Path,
    ore: str = "iron",
    score: int = 180,
    red_min: int = 180,
    green_max: int = 120,
    blue_max: int = 120,
    fit: str = "outline",
    svg_raster_scale: int = 4,
) -> None:
    manifest = read_manifest(manifest_path)
    red_mask, outline_bbox = _read_overlay_mask(
        image,
        red_min=red_min,
        green_max=green_max,
        blue_max=blue_max,
        manifest=manifest,
        svg_raster_scale=svg_raster_scale,
    )
    if not np.any(red_mask):
        console.print(f"[yellow]No red ore pixels found in {image}; {ore} tiles were unchanged.[/yellow]")
        return

    manifest.setdefault("ore_layers", {}).setdefault(ore, default_u8_layer(f"ores/{ore}"))
    layer = manifest["ore_layers"][ore]
    score = max(0, min(255, int(score)))
    changed_tiles, placement = _merge_mask_into_u8_layer(
        mask=red_mask,
        manifest=manifest,
        out=out,
        layer_name=ore,
        layer_path=layer["path"],
        layer_extension=layer.get("extension", u8_extension()),
        score=score,
        fit=fit,
        source_bbox=outline_bbox,
        target_bbox=_uk_reference_target_bbox(manifest) or _height_valid_bbox(out, manifest),
        control_matrix=_uk_reference_control_matrix(manifest, red_mask.shape[1], red_mask.shape[0]),
        desc=f"{ore} image overlay rows",
    )

    entry = {
        "ore": ore,
        "source": str(image),
        "score": score,
        "red_min": int(red_min),
        "green_max": int(green_max),
        "blue_max": int(blue_max),
        "fit": fit,
        "svg_raster_scale": int(svg_raster_scale),
        "placement": placement,
        "note": "Image red shapes were max-merged into the existing ore score tiles.",
    }
    overlays = [item for item in manifest.setdefault("ore_image_overlays", []) if item.get("ore") != ore]
    overlays.append(entry)
    manifest["ore_image_overlays"] = overlays
    write_manifest(manifest_path, manifest)
    console.print(f"{ore}: applied image overlay from {image} to {changed_tiles} tiles")


def apply_named_svg_ore_overlays(
    *,
    image: Path,
    manifest_path: Path,
    out: Path,
    overlays: Mapping[str, tuple[str, int]],
    fit: str = "full-frame",
    svg_raster_scale: int = 1,
) -> None:
    manifest = read_manifest(manifest_path)
    overlays_by_path: dict[str, list[tuple[str, int]]] = {}
    for ore, (path_id, score) in overlays.items():
        overlays_by_path.setdefault(path_id, []).append((ore, score))
    target_bbox = _full_target_bbox(manifest) if fit == "full-frame" else None
    fit_mode = "outline" if fit == "full-frame" else fit
    overlay_entries = [
        item for item in manifest.setdefault("ore_image_overlays", [])
        if item.get("source") != str(image) or item.get("kind") != "named_svg_paths"
    ]
    for path_id, ore_specs in overlays_by_path.items():
        mask = _read_named_svg_mask(image, path_id=path_id, svg_raster_scale=svg_raster_scale)
        source_bbox = _full_source_bbox(mask)
        for ore, raw_score in ore_specs:
            manifest.setdefault("ore_layers", {}).setdefault(ore, default_u8_layer(f"ores/{ore}"))
            layer = manifest["ore_layers"][ore]
            score = max(0, min(255, int(raw_score)))
            changed_tiles, _placement = _merge_mask_into_u8_layer(
                mask=mask,
                manifest=manifest,
                out=out,
                layer_name=ore,
                layer_path=layer["path"],
                layer_extension=layer.get("extension", u8_extension()),
                score=score,
                fit=fit_mode,
                source_bbox=source_bbox,
                target_bbox=target_bbox,
                control_matrix=None,
                desc=f"{ore} named SVG overlay rows",
            )
            overlay_entries.append({
                "kind": "named_svg_paths",
                "ore": ore,
                "source": str(image),
                "svg_path_id": path_id,
                "score": score,
                "fit": fit,
                "svg_raster_scale": int(svg_raster_scale),
                "note": "Named SVG path was max-merged into the existing ore score tiles.",
            })
            console.print(f"{ore}: applied SVG layer {path_id} from {image} to {changed_tiles} tiles")
    manifest["ore_image_overlays"] = overlay_entries
    write_manifest(manifest_path, manifest)


def _merge_mask_into_u8_layer(
    *,
    mask: np.ndarray,
    manifest: dict,
    out: Path,
    layer_name: str,
    layer_path: str,
    layer_extension: str,
    score: int,
    fit: str,
    source_bbox: BBox | None,
    target_bbox: BBox | None,
    control_matrix: np.ndarray | None,
    desc: str,
) -> tuple[int, dict[str, float | str | list[list[float]] | list[float]]]:
    tile_size = int(manifest["tile_size"])
    world = manifest["world"]
    width = int(world["width"])
    depth = int(world["depth"])
    padded_width = int(world["padded_width"])
    padded_depth = int(world["padded_depth"])
    tiles_x = math.ceil(padded_width / tile_size)
    tiles_z = math.ceil(padded_depth / tile_size)
    scale_x, scale_z, offset_x, offset_z, placement = _overlay_placement(
        mask.shape[1],
        mask.shape[0],
        width,
        depth,
        fit,
        source_bbox=source_bbox,
        target_bbox=target_bbox,
        control_matrix=control_matrix,
    )
    inverse_matrix = np.asarray(placement.get("inverse_matrix"), dtype=np.float64) if "inverse_matrix" in placement else None
    layer_root = out / layer_path
    layer_root.mkdir(parents=True, exist_ok=True)
    changed_tiles = 0
    for tile_z in tqdm(range(tiles_z), desc=desc):
        z = tile_z * tile_size + np.arange(tile_size)
        valid_z = z < depth
        if inverse_matrix is None:
            image_y_float = (z + 0.5 - offset_z) / scale_z
            valid_z &= (image_y_float >= 0) & (image_y_float < mask.shape[0])
            image_y = np.clip(image_y_float.astype(np.int64), 0, mask.shape[0] - 1)
        for tile_x in range(tiles_x):
            x = tile_x * tile_size + np.arange(tile_size)
            valid_x = x < width
            if inverse_matrix is None:
                if not np.any(valid_x) or not np.any(valid_z):
                    continue
                image_x_float = (x + 0.5 - offset_x) / scale_x
                valid_x &= (image_x_float >= 0) & (image_x_float < mask.shape[1])
                if not np.any(valid_x):
                    continue
                image_x = np.clip(image_x_float.astype(np.int64), 0, mask.shape[1] - 1)
                tile_mask = mask[np.ix_(image_y, image_x)]
                tile_mask &= valid_z[:, None]
                tile_mask &= valid_x[None, :]
            else:
                if not np.any(valid_x) or not np.any(valid_z):
                    continue
                grid_x, grid_z = np.meshgrid(x + 0.5, z + 0.5)
                image_x_float = inverse_matrix[0, 0] * grid_x + inverse_matrix[0, 1] * grid_z + inverse_matrix[0, 2]
                image_y_float = inverse_matrix[1, 0] * grid_x + inverse_matrix[1, 1] * grid_z + inverse_matrix[1, 2]
                valid = (
                    valid_z[:, None]
                    & valid_x[None, :]
                    & (image_x_float >= 0)
                    & (image_x_float < mask.shape[1])
                    & (image_y_float >= 0)
                    & (image_y_float < mask.shape[0])
                )
                image_x = np.clip(image_x_float.astype(np.int64), 0, mask.shape[1] - 1)
                image_y = np.clip(image_y_float.astype(np.int64), 0, mask.shape[0] - 1)
                tile_mask = mask[image_y, image_x] & valid
            if not np.any(tile_mask):
                continue
            path = layer_root / f"{tile_x:03d}_{tile_z:03d}{layer_extension}"
            if path.exists():
                tile = read_u8_tile(path, tile_size).copy()
            else:
                tile = np.zeros((tile_size, tile_size), dtype=np.uint8)
            before = tile.copy()
            tile[tile_mask] = np.maximum(tile[tile_mask], score)
            if not np.array_equal(tile, before):
                write_u8_tile(path, tile, tile_size)
                changed_tiles += 1
    return changed_tiles, placement


def _read_overlay_mask(
    path: Path,
    *,
    red_min: int,
    green_max: int,
    blue_max: int,
    manifest: dict | None = None,
    svg_raster_scale: int = 4,
) -> tuple[np.ndarray, BBox | None]:
    if path.suffix.lower() == ".svg":
        return _read_svg_overlay_mask(
            path,
            red_min=red_min,
            green_max=green_max,
            blue_max=blue_max,
            manifest=manifest,
            svg_raster_scale=svg_raster_scale,
        )
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
        & (red > blue * 3 // 2),
        None,
    )


def _read_svg_overlay_mask(
    path: Path,
    *,
    red_min: int,
    green_max: int,
    blue_max: int,
    manifest: dict | None = None,
    svg_raster_scale: int = 4,
) -> tuple[np.ndarray, BBox | None]:
    root = ET.parse(path).getroot()
    min_x, min_y, width, height = _svg_viewbox(root)
    base_source_width = max(1, math.ceil(width))
    base_source_height = max(1, math.ceil(height))
    raster_scale = max(1, int(svg_raster_scale))
    source_width = base_source_width * raster_scale
    source_height = base_source_height * raster_scale
    # Used only for the few deposits where you supplied Minecraft-coordinate
    # target boxes. If this SVG/manifest is not the checked UK iron reference,
    # this remains None and the rasteriser behaves as before. The snapping math
    # stays in the original SVG viewBox coordinate system; only the final mask is
    # supersampled to remove chunky SVG-pixel edges on the generated map.
    control_matrix = (
        _uk_reference_control_matrix(manifest, base_source_width, base_source_height)
        if manifest is not None
        else None
    )
    mask = Image.new("L", (source_width, source_height), 0)
    draw = ImageDraw.Draw(mask)
    outline_points: list[tuple[float, float]] = []
    for element in root.iter():
        if not element.tag.endswith("path"):
            continue
        d = element.attrib.get("d", "")
        polygons = _path_polygons(d)
        fill = _svg_paint(element, "fill")
        stroke = _svg_paint(element, "stroke")
        if _is_red_paint(fill, red_min=red_min, green_max=green_max, blue_max=blue_max):
            for polygon in polygons:
                if len(polygon) >= 3:
                    polygon = _snap_uk_iron_svg_polygon_to_minecraft(
                        polygon,
                        manifest=manifest,
                        control_matrix=control_matrix,
                    )
                    draw.polygon([((x - min_x) * raster_scale, (y - min_y) * raster_scale) for x, y in polygon], fill=255)
        if _is_blue_paint(stroke):
            outline_points.extend(point for polygon in polygons for point in polygon)
    outline_bbox = (
        _points_bbox(outline_points, min_x=min_x, min_y=min_y)
        if outline_points
        else None
    )
    return np.asarray(mask, dtype=np.uint8) > 0, outline_bbox


def _read_named_svg_mask(path: Path, *, path_id: str, svg_raster_scale: int = 1) -> np.ndarray:
    root = ET.parse(path).getroot()
    min_x, min_y, width, height = _svg_viewbox(root)
    raster_scale = _bounded_svg_raster_scale(width, height, svg_raster_scale)
    source_width = max(1, math.ceil(width)) * raster_scale
    source_height = max(1, math.ceil(height)) * raster_scale
    mask = Image.new("1", (source_width, source_height), 0)
    draw = ImageDraw.Draw(mask)
    found = False
    for element in root.iter():
        if not element.tag.endswith("path"):
            continue
        if element.attrib.get("id") != path_id:
            continue
        polygons = _path_polygons(element.attrib.get("d", ""))
        for polygon in polygons:
            if len(polygon) >= 3:
                draw.polygon([((x - min_x) * raster_scale, (y - min_y) * raster_scale) for x, y in polygon], fill=255)
        found = True
    if not found:
        raise ValueError(f"SVG path id {path_id!r} was not found in {path}")
    return np.asarray(mask, dtype=np.uint8) > 0


def _bounded_svg_raster_scale(width: float, height: float, requested_scale: int, *, max_pixels: int = 128_000_000) -> int:
    scale = max(1, int(requested_scale))
    base_pixels = max(1, math.ceil(width)) * max(1, math.ceil(height))
    if base_pixels * scale * scale <= max_pixels:
        return scale
    bounded = max(1, int(math.floor(math.sqrt(max_pixels / base_pixels))))
    if bounded < scale:
        console.print(f"[yellow]Reducing SVG raster scale from {scale} to {bounded} to stay within the memory guard.[/yellow]")
    return bounded


def _svg_viewbox(root: ET.Element) -> BBox:
    raw = root.attrib.get("viewBox")
    if raw:
        values = [float(v) for v in re.split(r"[\s,]+", raw.strip()) if v]
        if len(values) == 4:
            return values[0], values[1], values[2], values[3]
    return 0.0, 0.0, _svg_length(root.attrib.get("width", "1")), _svg_length(root.attrib.get("height", "1"))


def _svg_length(raw: str) -> float:
    match = re.match(r"\s*(-?\d+(?:\.\d+)?)", raw)
    return float(match.group(1)) if match else 1.0


def _svg_paint(element: ET.Element, key: str) -> str:
    if key in element.attrib:
        return element.attrib[key]
    style = element.attrib.get("style", "")
    for item in style.split(";"):
        raw_key, _, value = item.partition(":")
        if raw_key.strip() == key:
            return value.strip()
    return ""


def _is_red_paint(paint: str, *, red_min: int, green_max: int, blue_max: int) -> bool:
    color = _hex_color(paint)
    if color is None:
        return False
    red, green, blue = color
    return red >= red_min and green <= green_max and blue <= blue_max and red > green * 3 // 2 and red > blue * 3 // 2


def _is_blue_paint(paint: str) -> bool:
    color = _hex_color(paint)
    if color is None:
        return False
    red, green, blue = color
    return red <= 40 and green <= 40 and blue >= 180


def _hex_color(paint: str) -> tuple[int, int, int] | None:
    paint = paint.strip()
    if not paint.startswith("#"):
        return None
    value = paint.removeprefix("#")
    if len(value) == 3:
        value = "".join(c * 2 for c in value)
    if len(value) != 6:
        return None
    return int(value[0:2], 16), int(value[2:4], 16), int(value[4:6], 16)


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


def _points_bbox(points: list[tuple[float, float]], *, min_x: float, min_y: float) -> BBox:
    xs = [p[0] - min_x for p in points]
    ys = [p[1] - min_y for p in points]
    return min(xs), min(ys), max(xs), max(ys)


def _scale_bbox(bbox: BBox, scale: float) -> BBox:
    min_x, min_y, max_x, max_y = bbox
    return min_x * scale, min_y * scale, max_x * scale, max_y * scale


def _full_source_bbox(mask: np.ndarray) -> BBox:
    return 0.0, 0.0, float(mask.shape[1] - 1), float(mask.shape[0] - 1)


def _full_target_bbox(manifest: dict) -> BBox:
    world = manifest["world"]
    return 0.0, 0.0, float(int(world["width"]) - 1), float(int(world["depth"]) - 1)


# Exact local targets from in-game Minecraft X/Z boxes supplied for the checked
# UK iron reference SVG. The first BBox identifies the source SVG patch by its
# centre in viewBox pixels. The second BBox is a Minecraft-coordinate box in
# (x1, z1, x2, z2) form; corner order does not matter.
_UK_IRON_MINECRAFT_TARGET_BBOXES: tuple[tuple[str, BBox, BBox], ...] = (
    ("Ramsey", (336.0, 518.0, 341.0, 525.0), (-8127.0, -5630.0, -8127.0, -5630.0)),
    ("Millom", (418.0, 525.0, 424.0, 532.0), (-5479.0, -5300.0, -5479.0, -5300.0)),
    ("Furness", (429.0, 528.0, 437.0, 540.0), (-4902.0, -5149.0, -4902.0, -5149.0)),
    ("Perran", (286.0, 980.0, 304.0, 996.0), (-10515.0, 10635.0, -10461.0, 10346.0)),
    ("Brixham", (416.0, 979.0, 430.0, 991.0), (-6152.0, 11032.0, -6306.0, 10942.0)),
)


def _snap_uk_iron_svg_polygon_to_minecraft(
    polygon: list[tuple[float, float]],
    *,
    manifest: dict | None,
    control_matrix: np.ndarray | None,
) -> list[tuple[float, float]]:
    """Move selected SVG patches so their post-transform centres hit MC boxes.

    This is deliberately local: the global UK affine transform and all unrelated
    deposits stay unchanged. The conversion assumes the generated Minecraft map
    uses the raster centre as Minecraft (0, 0), i.e. raster x = mc_x + width/2
    and raster z = mc_z + depth/2, which matches the coordinate convention used
    by the generated maps in this project.
    """
    if manifest is None or control_matrix is None or len(polygon) < 3:
        return polygon
    world = manifest.get("world") or {}
    width = float(world.get("width", 0.0))
    depth = float(world.get("depth", 0.0))
    if width <= 0.0 or depth <= 0.0:
        return polygon

    src_bbox = _points_bbox(polygon, min_x=0.0, min_y=0.0)
    src_cx = (src_bbox[0] + src_bbox[2]) / 2.0
    src_cy = (src_bbox[1] + src_bbox[3]) / 2.0

    for _name, selector_bbox, minecraft_bbox in _UK_IRON_MINECRAFT_TARGET_BBOXES:
        sel_min_x, sel_min_y, sel_max_x, sel_max_y = selector_bbox
        if not (sel_min_x <= src_cx <= sel_max_x and sel_min_y <= src_cy <= sel_max_y):
            continue

        mc_x1, mc_z1, mc_x2, mc_z2 = minecraft_bbox
        desired_mc_x = (mc_x1 + mc_x2) / 2.0
        desired_mc_z = (mc_z1 + mc_z2) / 2.0
        desired_target = np.asarray(
            [desired_mc_x + width / 2.0, desired_mc_z + depth / 2.0],
            dtype=np.float64,
        )

        current_source = np.asarray([src_cx, src_cy, 1.0], dtype=np.float64)
        current_target = control_matrix @ current_source
        target_delta = desired_target - current_target

        linear = control_matrix[:, :2]
        try:
            source_delta = np.linalg.solve(linear, target_delta)
        except np.linalg.LinAlgError:
            return polygon

        dx, dy = float(source_delta[0]), float(source_delta[1])
        return [(x + dx, y + dy) for x, y in polygon]

    return polygon


def _overlay_placement(
    source_width: int,
    source_height: int,
    target_width: int,
    target_depth: int,
    fit: str,
    *,
    source_bbox: BBox | None = None,
    target_bbox: BBox | None = None,
    control_matrix: np.ndarray | None = None,
) -> tuple[float, float, float, float, dict[str, float | str]]:
    if fit == "outline" and control_matrix is not None:
        matrix_3x3 = np.vstack([control_matrix, [0.0, 0.0, 1.0]])
        inverse = np.linalg.inv(matrix_3x3)[:2, :]
        return (
            1.0,
            1.0,
            0.0,
            0.0,
            {
                "mode": "control_points",
                "matrix": control_matrix.tolist(),
                "inverse_matrix": inverse.tolist(),
            },
        )
    if fit == "outline" and source_bbox and target_bbox:
        src_min_x, src_min_y, src_max_x, src_max_y = source_bbox
        dst_min_x, dst_min_z, dst_max_x, dst_max_z = target_bbox
        scale_x = (dst_max_x - dst_min_x + 1.0) / max(1.0, src_max_x - src_min_x + 1.0)
        scale_z = (dst_max_z - dst_min_z + 1.0) / max(1.0, src_max_y - src_min_y + 1.0)
        return (
            scale_x,
            scale_z,
            dst_min_x - src_min_x * scale_x,
            dst_min_z - src_min_y * scale_z,
            {"mode": "outline", "source_bbox": list(source_bbox), "target_bbox": list(target_bbox)},
        )
    if fit == "outline":
        fit = "cover"
    if fit == "full-frame":
        fit = "outline"
    if fit == "contain":
        scale = min(target_width / source_width, target_depth / source_height)
    elif fit == "cover":
        scale = max(target_width / source_width, target_depth / source_height)
    else:
        raise ValueError("fit must be one of: outline, cover, contain")
    placed_width = source_width * scale
    placed_height = source_height * scale
    return (
        scale,
        scale,
        (target_width - placed_width) / 2.0,
        (target_depth - placed_height) / 2.0,
        {"mode": fit},
    )


def _height_valid_bbox(root: Path, manifest: dict) -> BBox | None:
    height = manifest.get("height")
    if not height:
        return None
    tile_size = int(manifest["tile_size"])
    width = int(manifest["world"]["width"])
    depth = int(manifest["world"]["depth"])
    height_root = root / height["path"]
    xs: list[int] = []
    zs: list[int] = []
    for path in list(height_root.glob("*.r16")) + list(height_root.glob("*.r16.gz")):
        try:
            name = path.name
            if name.endswith(".gz"):
                name = name[:-3]
            tile_x, tile_z = (int(part) for part in name.removesuffix(".r16").split("_"))
            arr = read_r16_tile(path, tile_size)
        except Exception:
            continue
        mask = arr != HEIGHT_NODATA
        if not np.any(mask):
            continue
        local_z, local_x = np.where(mask)
        global_x = tile_x * tile_size + local_x
        global_z = tile_z * tile_size + local_z
        inside = (global_x < width) & (global_z < depth)
        if not np.any(inside):
            continue
        xs.extend([int(global_x[inside].min()), int(global_x[inside].max())])
        zs.extend([int(global_z[inside].min()), int(global_z[inside].max())])
    if not xs or not zs:
        return None
    return float(min(xs)), float(min(zs)), float(max(xs)), float(max(zs))


def _uk_reference_target_bbox(manifest: dict) -> BBox | None:
    """Return the map frame used by the supplied historic UK iron reference SVG.

    The SVG's blue outline is a mainland/near-island UK reference drawing, not a
    full valid-height extent. Matching it to all non-nodata height cells uses
    far northern/padded islands as the vertical frame and leaves the iron
    districts too far north. These BNG bounds match the reference-map frame more
    closely while keeping the transform source-data driven and reproducible.
    """
    geo = manifest.get("georeferencing") or {}
    world = manifest.get("world") or {}
    if str(geo.get("crs", "")).upper() != "EPSG:27700":
        return None
    required = ("bng_min_easting", "bng_max_easting", "bng_min_northing", "bng_max_northing")
    if any(geo.get(key) is None for key in required):
        return None
    width = float(world.get("width", 0))
    depth = float(world.get("depth", 0))
    if width <= 0 or depth <= 0:
        return None

    min_e = float(geo["bng_min_easting"])
    max_e = float(geo["bng_max_easting"])
    min_n = float(geo["bng_min_northing"])
    max_n = float(geo["bng_max_northing"])

    def x_from_easting(easting: float) -> float:
        return (easting - min_e) * width / (max_e - min_e)

    def z_from_northing(northing: float) -> float:
        return (max_n - northing) * depth / (max_n - min_n)

    return (
        x_from_easting(39_000.0),
        z_from_northing(1_001_000.0),
        x_from_easting(650_000.0),
        z_from_northing(5_000.0),
    )


def _uk_reference_control_matrix(manifest: dict, source_width: int, source_height: int) -> np.ndarray | None:
    """Fit the supplied UK iron SVG to named-place BNG control points.

    The checked-in reference SVG has a 900 x 1044 viewBox. The rasteriser may
    supersample it to 1800/3600/etc. pixels to avoid jagged edges, so this
    accepts any near-integer multiple of that base size and scales the source
    control points accordingly.
    """
    base_width = 900.0
    base_height = 1044.0
    source_scale_x = float(source_width) / base_width
    source_scale_y = float(source_height) / base_height
    if abs(source_scale_x - source_scale_y) > 0.01:
        return None
    source_scale = (source_scale_x + source_scale_y) / 2.0
    if source_scale < 0.5 or abs(source_width - base_width * source_scale) > 2 or abs(source_height - base_height * source_scale) > 2:
        return None
    geo = manifest.get("georeferencing") or {}
    world = manifest.get("world") or {}
    if str(geo.get("crs", "")).upper() != "EPSG:27700":
        return None
    required = ("bng_min_easting", "bng_max_easting", "bng_min_northing", "bng_max_northing")
    if any(geo.get(key) is None for key in required):
        return None
    width = float(world.get("width", 0))
    depth = float(world.get("depth", 0))
    if width <= 0 or depth <= 0:
        return None

    min_e = float(geo["bng_min_easting"])
    max_e = float(geo["bng_max_easting"])
    min_n = float(geo["bng_min_northing"])
    max_n = float(geo["bng_max_northing"])

    def target(easting: float, northing: float) -> tuple[float, float]:
        return (
            (easting - min_e) * width / (max_e - min_e),
            (max_n - northing) * depth / (max_n - min_n),
        )

    def spt(x: float, y: float) -> tuple[float, float]:
        return x * source_scale, y * source_scale

    # Source coordinates are measured in the checked-in SVG viewBox, then scaled
    # if the SVG mask is being supersampled. Targets are approximate BNG
    # locations for the named iron districts on the reference image. The least-
    # squares affine fit keeps the whole overlay coherent while correcting the
    # residual scale/skew visible in the bbox-only placement.
    control_points = [
        (spt(337.0, 520.0), target(266_000.0, 483_000.0)),  # Ramsey, Isle of Man
        (spt(418.0, 495.0), target(301_000.0, 514_000.0)),  # Cleator Moor/Egremont
        (spt(433.0, 534.0), target(326_000.0, 482_000.0)),  # Millom/Furness
        (spt(610.0, 500.0), target(460_000.0, 516_000.0)),  # Cleveland
        (spt(773.0, 667.0), target(612_000.0, 342_000.0)),  # Weybourne
        (spt(705.0, 895.0), target(553_000.0, 119_600.0)),  # Low/High Weald
        (spt(291.0, 984.0), target(176_000.0, 54_000.0)),  # Perran
        (spt(352.0, 973.0), target(201_000.0, 68_000.0)),  # St Austell
        (spt(420.0, 985.0), target(292_000.0, 72_000.0)),  # Brixham
        (spt(638.0, 590.0), target(492_000.0, 395_000.0)),  # Frodingham
    ]
    source = np.asarray([[x, y, 1.0] for (x, y), _ in control_points], dtype=np.float64)
    destination = np.asarray([point for _, point in control_points], dtype=np.float64)
    return np.linalg.lstsq(source, destination, rcond=None)[0].T
