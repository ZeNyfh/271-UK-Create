from __future__ import annotations

from pathlib import Path
import math
import tempfile
import zipfile

import fiona
import geopandas as gpd
import numpy as np
from rasterio.enums import MergeAlg
from rasterio.features import rasterize
from rasterio.transform import from_bounds
from rich.console import Console
from tqdm import tqdm

from .manifest import default_u8_layer, read_manifest, write_manifest
from .tiles import write_u8_tile

console = Console()

COAL_LAYER_NAME = "coal"


def make_coal_resource_tiles(
    *,
    coal_resources: Path,
    manifest_path: Path,
    out: Path,
    debug_geotiff: Path | None = None,
) -> None:
    manifest = read_manifest(manifest_path)
    geo = manifest["georeferencing"]
    world = manifest["world"]
    tile_size = int(manifest["tile_size"])
    width = int(world["width"])
    depth = int(world["depth"])
    transform = from_bounds(
        geo["bng_min_easting"],
        geo["bng_min_northing"],
        geo["bng_max_easting"],
        geo["bng_max_northing"],
        width,
        depth,
    )
    arr = np.zeros((depth, width), dtype=np.uint8)
    datasets, tmp = _resolve_vector_datasets(coal_resources)
    try:
        bbox = (
            geo["bng_min_easting"],
            geo["bng_min_northing"],
            geo["bng_max_easting"],
            geo["bng_max_northing"],
        )
        for dataset in datasets:
            for layer_name in _coal_layers(dataset):
                try:
                    frame = gpd.read_file(dataset, layer=layer_name, bbox=bbox)
                except Exception as exc:
                    console.print(f"[yellow]Skipping {dataset.name}:{layer_name}: {exc}[/yellow]")
                    continue
                if frame.empty:
                    continue
                if frame.crs and str(frame.crs).upper() != "EPSG:27700":
                    frame = frame.to_crs("EPSG:27700")
                shapes = []
                for _, row in frame.iterrows():
                    geom = row.geometry
                    if geom is None or geom.is_empty:
                        continue
                    score = _score_coal_feature(layer_name, str(row.get("FEATURE", "")), str(row.get("RESOURCES", "")))
                    if score > 0:
                        shapes.append((geom, score))
                if shapes:
                    burned = rasterize(
                        shapes,
                        out_shape=arr.shape,
                        transform=transform,
                        fill=0,
                        dtype=np.uint8,
                        merge_alg=MergeAlg.replace,
                    )
                    arr = np.maximum(arr, burned)
        manifest.setdefault("ore_layers", {})
        manifest["ore_layers"][COAL_LAYER_NAME] = default_u8_layer(f"ores/{COAL_LAYER_NAME}")
        _write_tiles(arr, out / "ores" / COAL_LAYER_NAME, tile_size)
        if debug_geotiff:
            _write_debug(debug_geotiff, arr, transform)
        write_manifest(manifest_path, manifest)
        console.print("coal: wrote coal resource score tiles")
    finally:
        if tmp is not None:
            tmp.cleanup()


def _resolve_vector_datasets(path: Path) -> tuple[list[Path], tempfile.TemporaryDirectory[str] | None]:
    if path.suffix.lower() == ".gpkg":
        return [path], None
    if path.suffix.lower() == ".zip":
        tmp = tempfile.TemporaryDirectory()
        with zipfile.ZipFile(path) as zf:
            members = [n for n in zf.namelist() if n.lower().endswith(".gpkg")]
            if not members:
                tmp.cleanup()
                raise ValueError(f"No .gpkg found in {path}")
            zf.extractall(tmp.name, members)
        return [Path(tmp.name) / member for member in members], tmp
    raise ValueError("Coal resource input must be a .gpkg or .zip containing .gpkg files")


def _coal_layers(dataset: Path) -> list[str]:
    names = []
    for name in fiona.listlayers(dataset):
        if name.lower() == "coal-bearing strata":
            names.append(name)
    return names


def _score_coal_feature(layer_name: str, feature: str, resources: str = "") -> int:
    text = f"{layer_name} {feature} {resources}".lower()
    if "at surface" in text:
        return 255
    if "< 1200m" in text or "less than 1200" in text:
        return 190
    if "> 1200m" in text or "greater than 1200" in text:
        return 120
    if "coal-bearing strata" in text or "coal bearing strata" in text:
        return 160
    return 0


def _write_tiles(arr: np.ndarray, root: Path, tile_size: int) -> None:
    rows = range(math.ceil(arr.shape[0] / tile_size))
    for tile_z in tqdm(rows, desc=f"{root.name} tile rows"):
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
