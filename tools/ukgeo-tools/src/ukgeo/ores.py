from __future__ import annotations

from pathlib import Path
from concurrent.futures import ProcessPoolExecutor, as_completed
import math
import re

import geopandas as gpd
import numpy as np
import yaml
from rasterio.features import rasterize
from rasterio.enums import MergeAlg
from rasterio.transform import from_bounds
from rich.console import Console
from tqdm import tqdm

from .bgs import resolve_gpkg
from .manifest import ORE_NAMES, default_u8_layer, read_manifest, write_manifest
from .tiles import write_u8_tile

console = Console()


def make_ore_tiles(*, bgs: Path, rules: Path, manifest_path: Path, out: Path, debug_geotiff_dir: Path | None = None, jobs: int = 1) -> None:
    manifest = read_manifest(manifest_path)
    geo = manifest["georeferencing"]
    world = manifest["world"]
    tile_size = manifest["tile_size"]
    width = world["width"]
    depth = world["depth"]
    with rules.open("r", encoding="utf-8") as fh:
        config = yaml.safe_load(fh) or {}
    configured_layers = config.get("ores") or {}
    layer_names = list(dict.fromkeys([*ORE_NAMES, *configured_layers.keys()]))
    manifest.setdefault("ore_layers", {})
    for name in layer_names:
        manifest["ore_layers"].setdefault(name, default_u8_layer(f"ores/{name}"))
    jobs = max(1, int(jobs))
    gpkg, tmp = resolve_gpkg(bgs)
    try:
        tasks = [
            (
                ore,
                configured_layers.get(ore, {}),
                str(gpkg),
                geo,
                width,
                depth,
                tile_size,
                str(out),
                str(debug_geotiff_dir) if debug_geotiff_dir else None,
                jobs == 1,
            )
            for ore in layer_names
        ]
        if jobs == 1 or len(tasks) <= 1:
            for task in tqdm(tasks, desc="ore layers"):
                _print_worker_messages(_make_ore_layer(task))
        else:
            console.print(f"Generating {len(tasks)} ore/mineral layers with {jobs} worker processes.")
            with ProcessPoolExecutor(max_workers=jobs) as executor:
                futures = [executor.submit(_make_ore_layer, task) for task in tasks]
                for future in tqdm(as_completed(futures), total=len(futures), desc="ore layers"):
                    _print_worker_messages(future.result())
        write_manifest(manifest_path, manifest)
    finally:
        if tmp is not None:
            tmp.cleanup()


def _make_ore_layer(task: tuple) -> tuple[str, list[str]]:
    ore, layer_config, gpkg, geo, width, depth, tile_size, out, debug_geotiff_dir, show_tile_progress = task
    messages: list[str] = []
    transform = from_bounds(
        geo["bng_min_easting"],
        geo["bng_min_northing"],
        geo["bng_max_easting"],
        geo["bng_max_northing"],
        width,
        depth,
    )
    arr = np.zeros((depth, width), dtype=np.uint8)
    for layer_name in layer_config.get("layers", []):
        try:
            frame = gpd.read_file(gpkg, layer=layer_name, bbox=(geo["bng_min_easting"], geo["bng_min_northing"], geo["bng_max_easting"], geo["bng_max_northing"]))
        except Exception as exc:
            messages.append(f"[yellow]Skipping {layer_name}: {exc}[/yellow]")
            continue
        if frame.empty:
            continue
        if frame.crs and str(frame.crs).upper() != "EPSG:27700":
            frame = frame.to_crs("EPSG:27700")
        fields = layer_config.get("fields") or [c for c in frame.columns if c != frame.geometry.name]
        available_fields = [c for c in fields if c in frame.columns]
        if available_fields:
            text = frame[available_fields].fillna("").map(str).agg(" ".join, axis=1).str.lower()
        else:
            text = frame.geometry.astype(str).str.lower()
        shapes = []
        for group in layer_config.get("keyword_groups", []):
            keywords = group.get("keywords", [])
            score = int(group.get("score", layer_config.get("base_score", 80)))
            pattern = re.compile("|".join(re.escape(k.lower()) for k in keywords)) if keywords else None
            mask = text.str.contains(pattern, na=False) if pattern else np.zeros(len(frame), dtype=bool)
            shapes.extend((geom, score) for geom in frame.loc[mask, frame.geometry.name] if geom is not None and not geom.is_empty)
        if shapes:
            merge_alg = MergeAlg.add if str(layer_config.get("merge_alg", "replace")).lower() == "add" else MergeAlg.replace
            burned = rasterize(shapes, out_shape=arr.shape, transform=transform, fill=0, dtype=np.uint8, merge_alg=merge_alg)
            arr = np.maximum(arr, burned)
    arr = np.clip(arr, 0, int(layer_config.get("maximum_score", 255))).astype(np.uint8)
    _write_tiles(arr, Path(out) / "ores" / ore, tile_size, show_progress=show_tile_progress)
    if debug_geotiff_dir:
        _write_debug(Path(debug_geotiff_dir) / f"{ore}.tif", arr, transform)
    return ore, messages


def _print_worker_messages(result: tuple[str, list[str]]) -> None:
    ore, messages = result
    for message in messages:
        console.print(message)
    console.print(f"{ore}: wrote ore score tiles")


def _write_tiles(arr: np.ndarray, root: Path, tile_size: int, *, show_progress: bool = True) -> None:
    rows = range(math.ceil(arr.shape[0] / tile_size))
    if show_progress:
        rows = tqdm(rows, desc=f"{root.name} tile rows")
    for tile_z in rows:
        for tile_x in range(math.ceil(arr.shape[1] / tile_size)):
            tile = arr[tile_z * tile_size : (tile_z + 1) * tile_size, tile_x * tile_size : (tile_x + 1) * tile_size]
            if tile.shape != (tile_size, tile_size):
                padded = np.zeros((tile_size, tile_size), dtype=np.uint8)
                padded[: tile.shape[0], : tile.shape[1]] = tile
                tile = padded
            write_u8_tile(root / f"{tile_x:03d}_{tile_z:03d}.u8.gz", tile)


def _write_debug(path: Path, arr: np.ndarray, transform) -> None:
    import rasterio

    path.parent.mkdir(parents=True, exist_ok=True)
    with rasterio.open(path, "w", driver="GTiff", height=arr.shape[0], width=arr.shape[1], count=1, dtype="uint8", crs="EPSG:27700", transform=transform) as dst:
        dst.write(arr, 1)
