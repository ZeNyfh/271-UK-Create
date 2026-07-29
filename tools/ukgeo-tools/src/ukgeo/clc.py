from __future__ import annotations

from pathlib import Path
from urllib.parse import urlencode
from urllib.request import Request, urlopen
import hashlib
import io
import math
import time

import numpy as np
from PIL import Image
from rasterio.enums import Resampling
from rasterio.transform import from_bounds
from rasterio.warp import reproject, transform_bounds
from rich.console import Console
from tqdm import tqdm

from .manifest import read_manifest, write_manifest
from .tiles import write_u8_tile, u8_extension
from .vegetation import (
    BIOME_REGION_CLASSES,
    BIOME_REGION_DEFAULT_FACTOR,
    BIOME_REGION_HARD_CLASSES,
    VEGETATION_CLASSES,
    _write_vegetation_grid,
    cell_blocks_for_metres,
    generate_biome_region_grid,
)

console = Console()

CLC_WMS_URL = "https://image.discomap.eea.europa.eu/arcgis/services/Corine/CLC2018_WM/MapServer/WMSServer"
CLC_LAYER = "12"


CLC_RGB_TO_CODE: dict[tuple[int, int, int], int] = {
    (230, 0, 77): 111,
    (255, 0, 0): 112,
    (204, 77, 242): 121,
    (204, 0, 0): 122,
    (230, 204, 204): 123,
    (230, 204, 230): 124,
    (166, 0, 204): 131,
    (166, 77, 0): 132,
    (255, 77, 255): 133,
    (255, 166, 255): 141,
    (255, 230, 255): 142,
    (255, 255, 168): 211,
    (255, 255, 0): 212,
    (230, 230, 0): 213,
    (230, 128, 0): 221,
    (242, 166, 77): 222,
    (230, 166, 0): 223,
    (230, 230, 77): 231,
    (255, 230, 166): 241,
    (255, 230, 77): 242,
    (230, 204, 77): 243,
    (242, 204, 166): 244,
    (128, 255, 0): 311,
    (0, 166, 0): 312,
    (77, 255, 0): 313,
    (204, 242, 77): 321,
    (166, 255, 128): 322,
    (166, 230, 77): 323,
    (166, 242, 0): 324,
    (230, 230, 230): 331,
    (204, 204, 204): 332,
    (204, 255, 204): 333,
    (0, 0, 0): 334,
    (166, 230, 204): 335,
    (166, 166, 255): 411,
    (77, 77, 255): 412,
    (204, 204, 255): 421,
    (230, 230, 255): 422,
    (166, 166, 230): 423,
    (0, 204, 242): 511,
    (128, 242, 230): 512,
    (0, 255, 166): 521,
    (166, 255, 230): 522,
    (230, 242, 255): 523,
}

CLC_TO_VEGETATION: dict[int, int] = {
    111: 11,
    112: 11,
    121: 11,
    122: 11,
    123: 11,
    124: 11,
    131: 11,
    132: 11,
    133: 11,
    141: 11,
    142: 11,
    211: 3,
    212: 3,
    213: 3,
    221: 3,
    222: 3,
    223: 3,
    231: 4,
    241: 3,
    242: 3,
    243: 3,
    244: 3,
    311: 1,
    312: 2,
    313: 1,
    321: 7,
    322: 9,
    323: 9,
    324: 1,
    331: 12,
    332: 12,
    333: 12,
    334: 12,
    335: 12,
    411: 8,
    412: 8,
    421: 8,
    422: 8,
    423: 8,
    511: 10,
    512: 10,
    521: 0,
    522: 0,
    523: 0,
}

_RGB_LUT: np.ndarray | None = None


def make_clc_wms_vegetation_tiles(
    *,
    manifest_path: Path,
    out: Path,
    cache_dir: Path,
    cell_metres: float = 50.0,
    max_request_size: int = 4096,
    generate_biome_regions: bool = True,
    biome_region_factor: int = BIOME_REGION_DEFAULT_FACTOR,
    biome_region_smoothing_passes: int = 2,
    biome_region_min_area_cells: int = 3,
    wms_url: str = CLC_WMS_URL,
    layer: str = CLC_LAYER,
) -> None:
    manifest = read_manifest(manifest_path)
    geo = manifest["georeferencing"]
    world = manifest["world"]
    tile_size = int(manifest["tile_size"])
    width = int(world["width"])
    depth = int(world["depth"])
    padded_width = int(world["padded_width"])
    padded_depth = int(world["padded_depth"])
    cell_blocks = cell_blocks_for_metres(cell_metres, geo, width, depth)
    width_cells = math.ceil(width / cell_blocks)
    depth_cells = math.ceil(depth / cell_blocks)
    padded_width_cells = math.ceil(padded_width / cell_blocks)
    padded_depth_cells = math.ceil(padded_depth / cell_blocks)
    tiles_x = math.ceil(padded_width_cells / tile_size)
    tiles_z = math.ceil(padded_depth_cells / tile_size)
    group_tiles = max(1, int(max_request_size) // tile_size)

    console.print(
        "Generating CLC WMS vegetation: "
        f"{width_cells}x{depth_cells} cells, {cell_blocks} blocks/cell, "
        f"{tiles_x}x{tiles_z} tiles, {group_tiles}x{group_tiles} tiles/request."
    )

    cache_dir.mkdir(parents=True, exist_ok=True)
    root = out / "vegetation"
    root.mkdir(parents=True, exist_ok=True)
    vegetation_grid = np.zeros((depth_cells, width_cells), dtype=np.uint8)

    groups_x = math.ceil(tiles_x / group_tiles)
    groups_z = math.ceil(tiles_z / group_tiles)
    for group_z in tqdm(range(groups_z), desc="CLC WMS request rows"):
        for group_x in range(groups_x):
            tile_x0 = group_x * group_tiles
            tile_z0 = group_z * group_tiles
            cell_x0 = tile_x0 * tile_size
            cell_z0 = tile_z0 * tile_size
            group_w = min(group_tiles * tile_size, padded_width_cells - cell_x0)
            group_h = min(group_tiles * tile_size, padded_depth_cells - cell_z0)
            valid_w = max(0, min(group_w, width_cells - cell_x0))
            valid_h = max(0, min(group_h, depth_cells - cell_z0))
            group = np.zeros((group_h, group_w), dtype=np.uint8)
            if valid_w > 0 and valid_h > 0:
                west, south, east, north = _cell_window_bounds_bng(geo, width, depth, cell_blocks, cell_x0, cell_z0, valid_w, valid_h)
                bbox_3857 = transform_bounds("EPSG:27700", "EPSG:3857", west, south, east, north, densify_pts=21)
                png = _fetch_wms_png(
                    wms_url=wms_url,
                    layer=layer,
                    bbox=bbox_3857,
                    width=valid_w,
                    height=valid_h,
                    cache_dir=cache_dir,
                )
                source_classes = classify_clc_rgba(np.asarray(png.convert("RGBA")))
                reprojected = np.zeros((valid_h, valid_w), dtype=np.uint8)
                reproject(
                    source=source_classes,
                    destination=reprojected,
                    src_transform=from_bounds(*bbox_3857, valid_w, valid_h),
                    src_crs="EPSG:3857",
                    src_nodata=0,
                    dst_transform=from_bounds(west, south, east, north, valid_w, valid_h),
                    dst_crs="EPSG:27700",
                    dst_nodata=0,
                    resampling=Resampling.nearest,
                )
                group[:valid_h, :valid_w] = reprojected
                vegetation_grid[cell_z0 : cell_z0 + valid_h, cell_x0 : cell_x0 + valid_w] = group[:valid_h, :valid_w]
            _write_group_tiles(root, group, tile_x0, tile_z0, tile_size, group_tiles)

    manifest["vegetation"] = {
        "path": "vegetation",
        "extension": u8_extension(),
        "dtype": "uint8",
        "cell_blocks": cell_blocks,
        "cell_metres": cell_metres,
        "width_cells": width_cells,
        "depth_cells": depth_cells,
        "source": wms_url,
        "source_layer": layer,
        "source_scheme": "CORINE Land Cover 2018 WMS colors",
        "classes": {str(class_id): meta for class_id, meta in VEGETATION_CLASSES.items()},
        "source_classes": {str(code): f"CLC {code}" for code in sorted(CLC_TO_VEGETATION)},
    }

    if generate_biome_regions:
        region_factor = max(1, int(biome_region_factor))
        region_cell_blocks = cell_blocks * region_factor
        region_width_cells = math.ceil(width / region_cell_blocks)
        region_depth_cells = math.ceil(depth / region_cell_blocks)
        region_padded_width_cells = math.ceil(padded_width / region_cell_blocks)
        region_padded_depth_cells = math.ceil(padded_depth / region_cell_blocks)
        region_tiles_x = math.ceil(region_padded_width_cells / tile_size)
        region_tiles_z = math.ceil(region_padded_depth_cells / tile_size)
        console.print(
            "Generating CLC-derived biome region layer: "
            f"{region_width_cells}x{region_depth_cells} cells, {region_cell_blocks} blocks/cell."
        )
        biome_regions = generate_biome_region_grid(
            vegetation_grid,
            region_factor=region_factor,
            smoothing_passes=biome_region_smoothing_passes,
            min_area_cells=biome_region_min_area_cells,
        )
        region_root = out / "biome_regions"
        region_root.mkdir(parents=True, exist_ok=True)
        _write_vegetation_grid(region_root, biome_regions, region_tiles_x, region_tiles_z, tile_size, desc="write CLC biome region tiles")
        manifest["biome_regions"] = {
            "path": "biome_regions",
            "extension": u8_extension(),
            "dtype": "uint8",
            "cell_blocks": region_cell_blocks,
            "source_cell_blocks": cell_blocks,
            "region_factor": region_factor,
            "width_cells": region_width_cells,
            "depth_cells": region_depth_cells,
            "source": "vegetation",
            "classes": {str(class_id): meta for class_id, meta in BIOME_REGION_CLASSES.items()},
            "generation": {
                "method": "clc_wms_coarsened_group_majority_component_cleanup",
                "smoothing_passes": max(0, int(biome_region_smoothing_passes)),
                "min_area_cells": max(1, int(biome_region_min_area_cells)),
                "hard_classes": sorted(BIOME_REGION_HARD_CLASSES),
            },
        }

    write_manifest(manifest_path, manifest)


def classify_clc_rgba(rgba: np.ndarray) -> np.ndarray:
    if rgba.ndim != 3 or rgba.shape[2] < 4:
        raise ValueError("CLC image must be an RGBA array")
    lut = _rgb_lut()
    rgb = rgba[..., :3].astype(np.uint32, copy=False)
    packed = (rgb[..., 0] << 16) | (rgb[..., 1] << 8) | rgb[..., 2]
    out = lut[packed]
    out = out.astype(np.uint8, copy=True)
    out[rgba[..., 3] == 0] = 0
    return out


def _rgb_lut() -> np.ndarray:
    global _RGB_LUT
    if _RGB_LUT is None:
        lut = np.zeros(256 * 256 * 256, dtype=np.uint8)
        for rgb, code in CLC_RGB_TO_CODE.items():
            lut[(rgb[0] << 16) | (rgb[1] << 8) | rgb[2]] = np.uint8(CLC_TO_VEGETATION.get(code, 0))
        _RGB_LUT = lut
    return _RGB_LUT


def _cell_window_bounds_bng(
    geo: dict,
    world_width: int,
    world_depth: int,
    cell_blocks: int,
    cell_x0: int,
    cell_z0: int,
    cell_w: int,
    cell_h: int,
) -> tuple[float, float, float, float]:
    min_e = float(geo["bng_min_easting"])
    min_n = float(geo["bng_min_northing"])
    max_e = float(geo["bng_max_easting"])
    max_n = float(geo["bng_max_northing"])
    x0 = cell_x0 * cell_blocks
    z0 = cell_z0 * cell_blocks
    x1 = min(world_width, x0 + cell_w * cell_blocks)
    z1 = min(world_depth, z0 + cell_h * cell_blocks)
    west = min_e + (x0 / world_width) * (max_e - min_e)
    east = min_e + (x1 / world_width) * (max_e - min_e)
    north = max_n - (z0 / world_depth) * (max_n - min_n)
    south = max_n - (z1 / world_depth) * (max_n - min_n)
    return west, south, east, north


def _fetch_wms_png(
    *,
    wms_url: str,
    layer: str,
    bbox: tuple[float, float, float, float],
    width: int,
    height: int,
    cache_dir: Path,
    attempts: int = 4,
) -> Image.Image:
    bbox_text = ",".join(f"{value:.3f}" for value in bbox)
    params = {
        "service": "WMS",
        "version": "1.3.0",
        "request": "GetMap",
        "layers": layer,
        "styles": "",
        "crs": "EPSG:3857",
        "bbox": bbox_text,
        "width": str(int(width)),
        "height": str(int(height)),
        "format": "image/png",
        "transparent": "true",
    }
    url = wms_url + "?" + urlencode(params)
    key = hashlib.sha256(url.encode("utf-8")).hexdigest()
    cache_path = cache_dir / f"{key}.png"
    if cache_path.exists():
        return Image.open(cache_path).copy()
    last_exc: Exception | None = None
    for attempt in range(attempts):
        try:
            with urlopen(Request(url, headers={"User-Agent": "ukgeo-tools/1.0"}), timeout=90) as response:
                data = response.read()
            image = Image.open(io.BytesIO(data))
            image.load()
            cache_path.write_bytes(data)
            return image
        except Exception as exc:  # pragma: no cover - exercised only on network failure
            last_exc = exc
            time.sleep(min(2**attempt, 8))
    raise RuntimeError(f"CLC WMS request failed after {attempts} attempts: {url}") from last_exc


def _write_group_tiles(root: Path, group: np.ndarray, tile_x0: int, tile_z0: int, tile_size: int, group_tiles: int) -> None:
    for local_z in range(group_tiles):
        y0 = local_z * tile_size
        if y0 >= group.shape[0]:
            break
        for local_x in range(group_tiles):
            x0 = local_x * tile_size
            if x0 >= group.shape[1]:
                break
            tile = np.zeros((tile_size, tile_size), dtype=np.uint8)
            y1 = min(group.shape[0], y0 + tile_size)
            x1 = min(group.shape[1], x0 + tile_size)
            tile[: y1 - y0, : x1 - x0] = group[y0:y1, x0:x1]
            write_u8_tile(root / f"{tile_x0 + local_x:03d}_{tile_z0 + local_z:03d}{u8_extension()}", tile)
