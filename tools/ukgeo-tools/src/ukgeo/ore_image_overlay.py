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
from .tiles import HEIGHT_NODATA, read_r16_tile, read_u8_tile, write_u8_tile

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

    red_mask, outline_bbox = _read_overlay_mask(image, red_min=red_min, green_max=green_max, blue_max=blue_max)
    if not np.any(red_mask):
        console.print(f"[yellow]No red ore pixels found in {image}; {ore} tiles were unchanged.[/yellow]")
        return
    scale_x, scale_z, offset_x, offset_z, placement = _overlay_placement(
        red_mask.shape[1],
        red_mask.shape[0],
        width,
        depth,
        fit,
        source_bbox=outline_bbox,
        target_bbox=_uk_reference_target_bbox(manifest) or _height_valid_bbox(out, manifest),
        control_matrix=_uk_reference_control_matrix(manifest, red_mask.shape[1], red_mask.shape[0]),
    )
    inverse_matrix = np.asarray(placement.get("inverse_matrix"), dtype=np.float64) if "inverse_matrix" in placement else None

    manifest.setdefault("ore_layers", {}).setdefault(ore, default_u8_layer(f"ores/{ore}"))
    layer = manifest["ore_layers"][ore]
    layer_root = out / layer["path"]
    layer_root.mkdir(parents=True, exist_ok=True)
    score = max(0, min(255, int(score)))

    changed_tiles = 0
    for tile_z in tqdm(range(tiles_z), desc=f"{ore} image overlay rows"):
        z = tile_z * tile_size + np.arange(tile_size)
        valid_z = z < depth
        if inverse_matrix is None:
            image_y_float = (z + 0.5 - offset_z) / scale_z
            valid_z &= (image_y_float >= 0) & (image_y_float < red_mask.shape[0])
            image_y = np.clip(image_y_float.astype(np.int64), 0, red_mask.shape[0] - 1)
        for tile_x in range(tiles_x):
            x = tile_x * tile_size + np.arange(tile_size)
            valid_x = x < width
            if inverse_matrix is None:
                if not np.any(valid_x) or not np.any(valid_z):
                    continue
                image_x_float = (x + 0.5 - offset_x) / scale_x
                valid_x &= (image_x_float >= 0) & (image_x_float < red_mask.shape[1])
                if not np.any(valid_x):
                    continue
                image_x = np.clip(image_x_float.astype(np.int64), 0, red_mask.shape[1] - 1)
                mask = red_mask[np.ix_(image_y, image_x)]
                mask &= valid_z[:, None]
                mask &= valid_x[None, :]
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
                    & (image_x_float < red_mask.shape[1])
                    & (image_y_float >= 0)
                    & (image_y_float < red_mask.shape[0])
                )
                image_x = np.clip(image_x_float.astype(np.int64), 0, red_mask.shape[1] - 1)
                image_y = np.clip(image_y_float.astype(np.int64), 0, red_mask.shape[0] - 1)
                mask = red_mask[image_y, image_x] & valid
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

    entry = {
        "ore": ore,
        "source": str(image),
        "score": score,
        "red_min": int(red_min),
        "green_max": int(green_max),
        "blue_max": int(blue_max),
        "fit": fit,
        "placement": placement,
        "note": "Image red shapes were max-merged into the existing ore score tiles.",
    }
    overlays = [item for item in manifest.setdefault("ore_image_overlays", []) if item.get("ore") != ore]
    overlays.append(entry)
    manifest["ore_image_overlays"] = overlays
    write_manifest(manifest_path, manifest)
    console.print(f"{ore}: applied image overlay from {image} to {changed_tiles} tiles")


def _read_overlay_mask(path: Path, *, red_min: int, green_max: int, blue_max: int) -> tuple[np.ndarray, BBox | None]:
    if path.suffix.lower() == ".svg":
        return _read_svg_overlay_mask(path, red_min=red_min, green_max=green_max, blue_max=blue_max)
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


def _read_svg_overlay_mask(path: Path, *, red_min: int, green_max: int, blue_max: int) -> tuple[np.ndarray, BBox | None]:
    root = ET.parse(path).getroot()
    min_x, min_y, width, height = _svg_viewbox(root)
    mask = Image.new("L", (max(1, math.ceil(width)), max(1, math.ceil(height))), 0)
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
                    draw.polygon([(x - min_x, y - min_y) for x, y in polygon], fill=255)
        if _is_blue_paint(stroke):
            outline_points.extend(point for polygon in polygons for point in polygon)
    outline_bbox = _points_bbox(outline_points, min_x=min_x, min_y=min_y) if outline_points else None
    return np.asarray(mask, dtype=np.uint8) > 0, outline_bbox


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
    for path in height_root.glob("*.r16.gz"):
        try:
            tile_x, tile_z = (int(part) for part in path.name.removesuffix(".r16.gz").split("_"))
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
    """Fit the supplied UK iron SVG to named-place BNG control points."""
    if abs(source_width - 900) > 2 or abs(source_height - 1044) > 2:
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

    # Source coordinates are measured in the checked-in SVG viewBox. Targets are
    # approximate BNG locations for the named iron districts on the reference
    # image. The least-squares affine fit keeps the whole overlay coherent while
    # correcting the residual scale/skew visible in the bbox-only placement.
    control_points = [
        ((337.0, 520.0), target(266_000.0, 483_000.0)),  # Ramsey, Isle of Man
        ((418.0, 495.0), target(301_000.0, 514_000.0)),  # Cleator Moor/Egremont
        ((433.0, 534.0), target(326_000.0, 482_000.0)),  # Millom/Furness
        ((610.0, 500.0), target(460_000.0, 516_000.0)),  # Cleveland
        ((773.0, 667.0), target(612_000.0, 342_000.0)),  # Weybourne
        ((705.0, 895.0), target(540_000.0, 135_000.0)),  # Low/High Weald
        ((291.0, 984.0), target(176_000.0, 54_000.0)),  # Perran
        ((352.0, 973.0), target(201_000.0, 68_000.0)),  # St Austell
        ((420.0, 985.0), target(292_000.0, 72_000.0)),  # Brixham
        ((638.0, 590.0), target(492_000.0, 395_000.0)),  # Frodingham
    ]
    source = np.asarray([[x, y, 1.0] for (x, y), _ in control_points], dtype=np.float64)
    destination = np.asarray([point for _, point in control_points], dtype=np.float64)
    return np.linalg.lstsq(source, destination, rcond=None)[0].T
