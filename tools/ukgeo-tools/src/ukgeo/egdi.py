from __future__ import annotations

from pathlib import Path
from urllib.parse import urlencode
from urllib.request import Request, urlopen
import hashlib
import json
import math
import re
import time
from typing import Iterable

import geopandas as gpd
import numpy as np
import pandas as pd
from rasterio.enums import MergeAlg
from rasterio.features import rasterize
from rasterio.transform import from_bounds
from rasterio.warp import transform_bounds
from rich.console import Console
from shapely.geometry import box
from tqdm import tqdm

from .manifest import read_manifest, write_manifest
from .tiles import read_layer_tile, write_u8_tile, u8_extension

console = Console()

EGDI_WFS_URL = "https://maps.europe-geology.eu/wfs/"
EGDI_GEOLOGIC_UNIT_TYPE = "ms:geologicunitview"


EGDI_SURFACE_CLASSES: dict[int, dict[str, str]] = {
    10: {
        "name": "sand/gravel",
        "block": "minecraft:sand",
        "fallback_block": "minecraft:sand",
        "color": "#d7c58b",
    },
    11: {
        "name": "clay/silt/till",
        "block": "minecraft:clay",
        "fallback_block": "minecraft:clay",
        "color": "#9a8f7a",
    },
    12: {
        "name": "peat",
        "block": "minecraft:mud",
        "fallback_block": "minecraft:dirt",
        "color": "#4b3728",
    },
}

_CLASS_RULES: list[tuple[int, tuple[str, ...]]] = [
    (12, ("peat",)),
    (1, ("chalk",)),
    (2, ("limestone", "dolostone", "dolomite", "calcareous", "carbonate")),
    (7, ("tuff", "pyroclastic", "volcaniclastic", "volcanic ash")),
    (3, ("granite", "granitic", "granitoid", "rhyolite", "felsic igneous")),
    (6, ("diorite",)),
    (5, ("andesite", "andesitic", "intermediate igneous")),
    (4, ("basalt", "basaltic", "dolerite", "doleritic", "mafic", "gabbro", "finegrainedigneousrock", "fine grained igneous")),
    (8, ("sandstone", "quartzite", "wacke", "conglomerate", "breccia")),
    (10, ("sand and gravel", "sand/gravel", "gravel", "sand")),
    (11, ("diamicton", "till", "clay", "silt", "mud")),
    (9, ("slate", "mudstone", "siltstone", "shale", "schist", "gneiss", "metamorphic")),
]


def add_egdi_surface_geology_tiles(
    *,
    manifest_path: Path,
    out: Path,
    cache_dir: Path,
    feature_types: Iterable[str] = (EGDI_GEOLOGIC_UNIT_TYPE,),
    fill_only: bool = True,
    page_size: int = 5000,
    wfs_url: str = EGDI_WFS_URL,
) -> None:
    manifest = read_manifest(manifest_path)
    geo = manifest["georeferencing"]
    world = manifest["world"]
    tile_size = int(manifest["tile_size"])
    width = int(world["width"])
    depth = int(world["depth"])
    tiles_x = math.ceil(int(world["padded_width"]) / tile_size)
    tiles_z = math.ceil(int(world["padded_depth"]) / tile_size)
    bbox_axis = _manifest_bbox_epsg4326_axis_order(geo)

    frames: list[gpd.GeoDataFrame] = []
    for feature_type in feature_types:
        path = fetch_wfs_geojson(
            wfs_url=wfs_url,
            feature_type=feature_type,
            bbox_axis=bbox_axis,
            cache_dir=cache_dir,
            page_size=page_size,
        )
        frame = gpd.read_file(path)
        if frame.empty:
            continue
        if frame.crs is None:
            frame = frame.set_crs("EPSG:4326")
        frame = frame.to_crs("EPSG:27700")
        frame["surface_class"] = frame.apply(_classify_feature_row, axis=1).astype(np.uint8)
        frame = frame[(frame["surface_class"] > 0) & frame.geometry.notna() & ~frame.geometry.is_empty]
        if not frame.empty:
            frames.append(frame[["surface_class", "geometry"]])
            console.print(f"{feature_type}: loaded {len(frame)} classifiable features from {path}")

    if not frames:
        console.print("[yellow]No EGDI features were classifiable; surface geology unchanged.[/yellow]")
        return

    geology = gpd.GeoDataFrame(pd.concat(frames, ignore_index=True), crs="EPSG:27700")
    geology = geology.cx[
        float(geo["bng_min_easting"]) : float(geo["bng_max_easting"]),
        float(geo["bng_min_northing"]) : float(geo["bng_max_northing"]),
    ]
    if geology.empty:
        console.print("[yellow]No EGDI features intersect the manifest BNG bounds; surface geology unchanged.[/yellow]")
        return

    sindex = geology.sindex
    surface_layer = manifest.get("surface_geology") or {
        "path": "geology/surface",
        "extension": u8_extension(),
        "dtype": "uint8",
        "classes": {},
    }
    root = out / surface_layer.get("path", "geology/surface")
    root.mkdir(parents=True, exist_ok=True)
    extension = surface_layer.get("extension", u8_extension())
    changed_tiles = 0
    changed_cells = 0

    for tile_z in tqdm(range(tiles_z), desc="EGDI surface geology tile rows"):
        for tile_x in range(tiles_x):
            west, south, east, north = _tile_bounds_bng(geo, width, depth, tile_x, tile_z, tile_size)
            candidates = list(sindex.query(box(west, south, east, north), predicate="intersects"))
            existing = read_layer_tile(out, surface_layer, tile_x, tile_z, tile_size)
            if candidates:
                frame = geology.iloc[candidates]
                transform = from_bounds(west, south, east, north, tile_size, tile_size)
                burn = rasterize(
                    ((geom, int(class_id)) for geom, class_id in zip(frame.geometry, frame["surface_class"], strict=False)),
                    out_shape=(tile_size, tile_size),
                    transform=transform,
                    fill=0,
                    dtype=np.uint8,
                    merge_alg=MergeAlg.replace,
                )
                if fill_only:
                    updated = existing.copy()
                    replace = (updated == 0) & (burn != 0)
                    if np.any(replace):
                        updated[replace] = burn[replace]
                        changed_cells += int(np.count_nonzero(replace))
                else:
                    updated = existing.copy()
                    replace = burn != 0
                    if np.any(replace):
                        changed_cells += int(np.count_nonzero(replace & (burn != existing)))
                        updated[replace] = burn[replace]
            else:
                updated = existing
            if not np.array_equal(updated, existing):
                changed_tiles += 1
            write_u8_tile(root / f"{tile_x:03d}_{tile_z:03d}{extension}", updated, tile_size)

    classes = {str(key): value for key, value in surface_layer.get("classes", {}).items()}
    for class_id, meta in EGDI_SURFACE_CLASSES.items():
        classes.setdefault(str(class_id), meta)
    surface_layer.update(
        {
            "path": surface_layer.get("path", "geology/surface"),
            "extension": extension,
            "dtype": "uint8",
            "classes": classes,
            "egdi_overlay": {
                "source": wfs_url,
                "feature_types": list(feature_types),
                "mode": "fill_only" if fill_only else "replace_nonzero",
                "bbox_epsg4326_axis_order": bbox_axis,
                "changed_tiles": changed_tiles,
                "changed_cells": changed_cells,
            },
        }
    )
    manifest["surface_geology"] = surface_layer
    write_manifest(manifest_path, manifest)
    console.print(f"EGDI surface geology overlay changed {changed_cells} cells in {changed_tiles} tiles.")


def fetch_wfs_geojson(
    *,
    wfs_url: str,
    feature_type: str,
    bbox_axis: str,
    cache_dir: Path,
    page_size: int = 5000,
) -> Path:
    cache_dir.mkdir(parents=True, exist_ok=True)
    cache_key = hashlib.sha256(f"{wfs_url}|{feature_type}|{bbox_axis}|{page_size}".encode("utf-8")).hexdigest()
    path = cache_dir / f"{feature_type.replace(':', '_')}_{cache_key[:12]}.geojson"
    if path.exists():
        return path

    features: list[dict] = []
    start = 0
    while True:
        params = {
            "service": "WFS",
            "version": "2.0.0",
            "request": "GetFeature",
            "typeNames": feature_type,
            "outputFormat": "application/json; charset=utf-8 subtype=geojson",
            "count": str(int(page_size)),
            "startIndex": str(start),
            "bbox": bbox_axis,
        }
        data = _fetch_bytes(wfs_url + "?" + urlencode(params))
        collection = json.loads(data.decode("utf-8"))
        page = collection.get("features", [])
        features.extend(page)
        if len(page) < page_size:
            break
        start += page_size
    path.write_text(
        json.dumps({"type": "FeatureCollection", "name": feature_type.replace("ms:", ""), "features": features}),
        encoding="utf-8",
    )
    return path


def _fetch_bytes(url: str, attempts: int = 4) -> bytes:
    last_exc: Exception | None = None
    for attempt in range(attempts):
        try:
            with urlopen(Request(url, headers={"User-Agent": "ukgeo-tools/1.0"}), timeout=120) as response:
                return response.read()
        except Exception as exc:  # pragma: no cover - exercised only on network failure
            last_exc = exc
            time.sleep(min(2**attempt, 8))
    raise RuntimeError(f"EGDI WFS request failed after {attempts} attempts: {url}") from last_exc


def _classify_feature_row(row) -> int:
    parts: list[str] = []
    for key in (
        "representativelithology_title",
        "representativelithology_uri",
        "main_lithogy",
        "lithology_description",
        "description",
        "name",
    ):
        value = row.get(key)
        if value is not None:
            parts.append(str(value))
    return classify_lithology_text(" ".join(parts))


def classify_lithology_text(text: str) -> int:
    normalized = _normalize(text)
    if not normalized:
        return 0
    for class_id, keywords in _CLASS_RULES:
        for keyword in keywords:
            if _normalize(keyword) in normalized:
                return class_id
    return 0


def _normalize(text: str) -> str:
    return re.sub(r"[^a-z0-9]+", " ", text.lower()).strip()


def _manifest_bbox_epsg4326_axis_order(geo: dict) -> str:
    west, south, east, north = transform_bounds(
        "EPSG:27700",
        "EPSG:4326",
        float(geo["bng_min_easting"]),
        float(geo["bng_min_northing"]),
        float(geo["bng_max_easting"]),
        float(geo["bng_max_northing"]),
        densify_pts=21,
    )
    return f"{south:.8f},{west:.8f},{north:.8f},{east:.8f},EPSG:4326"


def _tile_bounds_bng(geo: dict, world_width: int, world_depth: int, tile_x: int, tile_z: int, tile_size: int) -> tuple[float, float, float, float]:
    min_e = float(geo["bng_min_easting"])
    min_n = float(geo["bng_min_northing"])
    max_e = float(geo["bng_max_easting"])
    max_n = float(geo["bng_max_northing"])
    x0 = tile_x * tile_size
    z0 = tile_z * tile_size
    x1 = x0 + tile_size
    z1 = z0 + tile_size
    west = min_e + (x0 / world_width) * (max_e - min_e)
    east = min_e + (x1 / world_width) * (max_e - min_e)
    north = max_n - (z0 / world_depth) * (max_n - min_n)
    south = max_n - (z1 / world_depth) * (max_n - min_n)
    return west, south, east, north
