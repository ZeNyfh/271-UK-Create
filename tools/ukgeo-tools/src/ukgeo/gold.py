from __future__ import annotations

from collections import deque
from pathlib import Path
import json
import math
import time
import urllib.parse
import urllib.request

import numpy as np
from PIL import Image
from rich.console import Console
from tqdm import tqdm

from .manifest import default_u8_layer, read_manifest, write_manifest
from .tiles import read_u8_tile, write_u8_tile

console = Console()

WMS_URL = "https://map.bgs.ac.uk/arcgis/services/GeoIndex_Onshore/minerals_wms/MapServer/WMSServer"
GOLD_LAYERS = ("Mineral.Occurrences", "Metallic.Minerals.Wales")


def harvest_gold_occurrences(
    *,
    out: Path,
    bng_min_easting: float = 0.0,
    bng_min_northing: float = 0.0,
    bng_max_easting: float = 650000.0,
    bng_max_northing: float = 1300000.0,
    tile_metres: float = 50_000.0,
    pixel_metres: float = 100.0,
    request_pause: float = 0.02,
    limit_tiles: int | None = None,
) -> Path:
    records: dict[str, dict] = {}
    tiles = []
    x_count = math.ceil((bng_max_easting - bng_min_easting) / tile_metres)
    y_count = math.ceil((bng_max_northing - bng_min_northing) / tile_metres)
    for iy in range(y_count):
        y0 = bng_min_northing + iy * tile_metres
        y1 = min(bng_max_northing, y0 + tile_metres)
        for ix in range(x_count):
            x0 = bng_min_easting + ix * tile_metres
            x1 = min(bng_max_easting, x0 + tile_metres)
            tiles.append((x0, y0, x1, y1))
    if limit_tiles is not None:
        tiles = tiles[:limit_tiles]

    for layer in GOLD_LAYERS:
        console.print(f"Harvesting {layer} from BGS WMS over {len(tiles)} tiles.")
        for bbox in tqdm(tiles, desc=layer):
            width = max(1, math.ceil((bbox[2] - bbox[0]) / pixel_metres))
            height = max(1, math.ceil((bbox[3] - bbox[1]) / pixel_metres))
            image = _get_map(layer, bbox, width, height)
            candidates = _candidate_pixels(image)
            for px, py in candidates:
                for feature in _get_feature_info(layer, bbox, width, height, px, py):
                    record = _gold_record(feature)
                    if record is None:
                        continue
                    records[_record_key(record)] = record
                if request_pause > 0:
                    time.sleep(request_pause)

    features = [
        {
            "type": "Feature",
            "geometry": {"type": "Point", "coordinates": [record["easting"], record["northing"]]},
            "properties": {key: value for key, value in record.items() if key not in {"easting", "northing"}},
        }
        for record in sorted(records.values(), key=lambda item: (item["easting"], item["northing"], item["name"]))
    ]
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w", encoding="utf-8") as fh:
        json.dump(
            {
                "type": "FeatureCollection",
                "name": "bgs_gold_occurrences_from_wms",
                "crs": {"type": "name", "properties": {"name": "EPSG:27700"}},
                "features": features,
            },
            fh,
            indent=2,
        )
        fh.write("\n")
    console.print(f"Wrote {len(features)} unique gold occurrences to {out}")
    return out


def make_gold_occurrence_tiles(
    *,
    gold_occurrences: Path,
    manifest_path: Path,
    out: Path,
    radius_metres: float = 4500.0,
    core_metres: float = 900.0,
    merge_existing: bool = True,
) -> None:
    manifest = read_manifest(manifest_path)
    geo = manifest["georeferencing"]
    world = manifest["world"]
    tile_size = int(manifest["tile_size"])
    width = int(world["width"])
    depth = int(world["depth"])
    x_scale = (float(geo["bng_max_easting"]) - float(geo["bng_min_easting"])) / width
    z_scale = (float(geo["bng_max_northing"]) - float(geo["bng_min_northing"])) / depth
    radius_cells = max(1, int(math.ceil(radius_metres / x_scale)))
    core_cells = max(1, int(math.ceil(core_metres / x_scale)))

    points = _read_gold_points(gold_occurrences)
    tiles_x = math.ceil(int(world["padded_width"]) / tile_size)
    tiles_z = math.ceil(int(world["padded_depth"]) / tile_size)
    tile_arrays: dict[tuple[int, int], np.ndarray] = {}
    for easting, northing, _props in points:
        data_x = int((easting - float(geo["bng_min_easting"])) / x_scale)
        data_z = int((float(geo["bng_max_northing"]) - northing) / z_scale)
        if data_x < 0 or data_z < 0 or data_x >= width or data_z >= depth:
            continue
        tx0 = max(0, (data_x - radius_cells) // tile_size)
        tx1 = min(tiles_x - 1, (data_x + radius_cells) // tile_size)
        tz0 = max(0, (data_z - radius_cells) // tile_size)
        tz1 = min(tiles_z - 1, (data_z + radius_cells) // tile_size)
        for tz in range(tz0, tz1 + 1):
            for tx in range(tx0, tx1 + 1):
                arr = tile_arrays.setdefault((tx, tz), np.zeros((tile_size, tile_size), dtype=np.uint8))
                gx = tx * tile_size + np.arange(tile_size)
                gz = tz * tile_size + np.arange(tile_size)
                dx = gx[None, :] - data_x
                dz = gz[:, None] - data_z
                distance = np.sqrt(dx * dx + dz * dz)
                mask = distance <= radius_cells
                if not mask.any():
                    continue
                falloff = np.clip((radius_cells - distance) / max(1, radius_cells - core_cells), 0.0, 1.0)
                score = np.where(distance <= core_cells, 255, np.maximum(90, np.round(255 * falloff))).astype(np.uint8)
                arr[mask] = np.maximum(arr[mask], score[mask])

    layer_root = out / "ores" / "gold"
    for tz in tqdm(range(tiles_z), desc="gold tile rows"):
        for tx in range(tiles_x):
            tile_path = layer_root / f"{tx:03d}_{tz:03d}.u8.gz"
            tile = tile_arrays.get((tx, tz), np.zeros((tile_size, tile_size), dtype=np.uint8))
            if merge_existing and tile_path.exists():
                tile = np.maximum(read_u8_tile(tile_path), tile)
            write_u8_tile(tile_path, tile)
    manifest.setdefault("ore_layers", {})["gold"] = default_u8_layer("ores/gold")
    write_manifest(manifest_path, manifest)
    console.print(f"gold: wrote occurrence score tiles from {len(points)} points")


def _get_map(layer: str, bbox: tuple[float, float, float, float], width: int, height: int) -> Image.Image:
    params = {
        "service": "WMS",
        "version": "1.3.0",
        "request": "GetMap",
        "layers": layer,
        "styles": "",
        "crs": "EPSG:27700",
        "bbox": ",".join(str(v) for v in bbox),
        "width": str(width),
        "height": str(height),
        "format": "image/png32",
        "transparent": "true",
        "layerDefs": json.dumps({layer: _gold_where_clause(layer)}),
    }
    with urllib.request.urlopen(WMS_URL + "?" + urllib.parse.urlencode(params), timeout=60) as response:
        return Image.open(response).convert("RGBA")


def _get_feature_info(layer: str, bbox: tuple[float, float, float, float], width: int, height: int, px: int, py: int) -> list[dict]:
    params = {
        "service": "WMS",
        "version": "1.3.0",
        "request": "GetFeatureInfo",
        "layers": layer,
        "query_layers": layer,
        "styles": "",
        "crs": "EPSG:27700",
        "bbox": ",".join(str(v) for v in bbox),
        "width": str(width),
        "height": str(height),
        "i": str(px),
        "j": str(py),
        "info_format": "application/geo+json",
        "feature_count": "50",
        "layerDefs": json.dumps({layer: _gold_where_clause(layer)}),
    }
    try:
        with urllib.request.urlopen(WMS_URL + "?" + urllib.parse.urlencode(params), timeout=60) as response:
            data = json.loads(response.read().decode("utf-8", "replace"))
    except Exception:
        return []
    return data.get("features") or []


def _candidate_pixels(image: Image.Image) -> list[tuple[int, int]]:
    alpha = np.array(image, dtype=np.uint8)[:, :, 3]
    mask = alpha > 0
    height, width = mask.shape
    seen = np.zeros(mask.shape, dtype=bool)
    candidates: list[tuple[int, int]] = []
    for y in range(height):
        for x in range(width):
            if not mask[y, x] or seen[y, x]:
                continue
            pixels = []
            queue = deque([(x, y)])
            seen[y, x] = True
            while queue:
                cx, cy = queue.popleft()
                pixels.append((cx, cy))
                for nx in (cx - 1, cx, cx + 1):
                    for ny in (cy - 1, cy, cy + 1):
                        if nx < 0 or ny < 0 or nx >= width or ny >= height or seen[ny, nx] or not mask[ny, nx]:
                            continue
                        seen[ny, nx] = True
                        queue.append((nx, ny))
            if len(pixels) < 2:
                continue
            xs = [p[0] for p in pixels]
            ys = [p[1] for p in pixels]
            x0, x1 = min(xs), max(xs)
            y0, y1 = min(ys), max(ys)
            candidates.append((round(sum(xs) / len(xs)), round(sum(ys) / len(ys))))
            if max(x1 - x0, y1 - y0) > 14:
                for sy in range(y0, y1 + 1, 10):
                    for sx in range(x0, x1 + 1, 10):
                        if mask[sy, sx]:
                            candidates.append((sx, sy))
    return list(dict.fromkeys(candidates))


def _gold_record(feature: dict) -> dict | None:
    props = feature.get("properties") or {}
    commodity = str(props.get("COMMODITY") or "")
    element = str(props.get("ELEMENT") or "")
    symbol = str(props.get("SYMBOL") or "")
    if "gold" not in f"{commodity} {element}".lower() and symbol.lower() != "au":
        return None
    easting = _number(props.get("EASTING") or props.get("BNG_EASTIN"))
    northing = _number(props.get("NORTHING") or props.get("BNG_NORTHI"))
    if easting is None or northing is None:
        return None
    name = str(props.get("OCCURRENCE") or props.get("LOCALITY_D") or "")
    if "test" in name.lower() and "delete" in name.lower():
        return None
    return {
        "source_layer": feature.get("layerName", ""),
        "occurrence_id": str(props.get("OCCURRENCE_ID") or props.get("OCC_ID") or ""),
        "name": name,
        "commodity": commodity or element or "Gold",
        "symbol": symbol or ("Au" if "gold" in f"{commodity} {element}".lower() else ""),
        "easting": int(round(easting)),
        "northing": int(round(northing)),
    }


def _number(value) -> float | None:
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def _record_key(record: dict) -> str:
    if record["occurrence_id"]:
        return f"{record['source_layer']}:{record['occurrence_id']}"
    return f"{record['source_layer']}:{record['easting']}:{record['northing']}:{record['name']}"


def _read_gold_points(path: Path) -> list[tuple[float, float, dict]]:
    data = json.loads(path.read_text(encoding="utf-8"))
    points = []
    for feature in data.get("features", []):
        geom = feature.get("geometry") or {}
        coords = geom.get("coordinates") or []
        if len(coords) < 2:
            continue
        points.append((float(coords[0]), float(coords[1]), feature.get("properties") or {}))
    return points


def _gold_where_clause(layer: str) -> str:
    if layer == "Mineral.Occurrences":
        return "COMMODITY = 'Gold'"
    if layer == "Metallic.Minerals.Wales":
        return "ELEMENT = 'Gold'"
    return "1=1"
