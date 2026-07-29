from __future__ import annotations

import argparse
import json
import math
from functools import lru_cache
from pathlib import Path

import numpy as np
from scipy.ndimage import distance_transform_edt

from ukgeo.tiles import read_layer_tile, region_metadata, river_u8_layer, u8_extension, write_region_file_from_tiles


def _layer_paths(rivers: dict) -> dict[str, dict]:
    return {
        "rivers": river_u8_layer(rivers),
        "order": river_u8_layer(rivers, "order_path", "order"),
        "half_width": river_u8_layer(rivers, "half_width_path", "half_width"),
        "preview_radius": river_u8_layer(rivers, "preview_radius_path", "preview_radius"),
    }


def _region_file(root: Path, layer: dict, region_x: int, region_z: int) -> Path:
    region_path = str(layer.get("region_path", f"{layer['path']}/regions"))
    extension = str(layer.get("region_extension", ".u8rg"))
    return root / region_path / f"{region_x:03d}_{region_z:03d}{extension}"


def _halo_stack(read_tile, layer_name: str, tile_x: int, tile_z: int, tiles_x: int, tiles_z: int, tile_size: int, halo: int) -> np.ndarray:
    size = tile_size + halo * 2
    out = np.zeros((size, size), dtype=np.uint8)
    world_x0 = tile_x * tile_size - halo
    world_z0 = tile_z * tile_size - halo
    world_x1 = world_x0 + size
    world_z1 = world_z0 + size
    src_tx0 = max(0, world_x0 // tile_size)
    src_tz0 = max(0, world_z0 // tile_size)
    src_tx1 = min(tiles_x - 1, (world_x1 - 1) // tile_size)
    src_tz1 = min(tiles_z - 1, (world_z1 - 1) // tile_size)
    for src_tz in range(src_tz0, src_tz1 + 1):
        for src_tx in range(src_tx0, src_tx1 + 1):
            tile = read_tile(layer_name, src_tx, src_tz)
            tx0 = src_tx * tile_size
            tz0 = src_tz * tile_size
            ix0 = max(world_x0, tx0)
            iz0 = max(world_z0, tz0)
            ix1 = min(world_x1, tx0 + tile_size)
            iz1 = min(world_z1, tz0 + tile_size)
            if ix0 >= ix1 or iz0 >= iz1:
                continue
            out_z0 = iz0 - world_z0
            out_x0 = ix0 - world_x0
            tile_z0 = iz0 - tz0
            tile_x0 = ix0 - tx0
            out[out_z0 : out_z0 + (iz1 - iz0), out_x0 : out_x0 + (ix1 - ix0)] = tile[
                tile_z0 : tile_z0 + (iz1 - iz0), tile_x0 : tile_x0 + (ix1 - ix0)
            ]
    return out


def shrink_rivers(src: Path, dst: Path, *, factor: float, halo: int) -> None:
    manifest = json.loads((src / "manifest.json").read_text())
    rivers = manifest["rivers"]
    layers = _layer_paths(rivers)
    tile_size = int(manifest["tile_size"])
    region_tiles = int(rivers.get("region_tiles", 8))
    padded_width = int(manifest["world"]["padded_width"])
    padded_depth = int(manifest["world"]["padded_depth"])
    tiles_x = math.ceil(padded_width / tile_size)
    tiles_z = math.ceil(padded_depth / tile_size)
    regions_x = math.ceil(tiles_x / region_tiles)
    regions_z = math.ceil(tiles_z / region_tiles)
    dst.mkdir(parents=True, exist_ok=True)
    (dst / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")

    @lru_cache(maxsize=2048)
    def read_tile(layer_name: str, tile_x: int, tile_z: int) -> np.ndarray:
        if tile_x < 0 or tile_z < 0 or tile_x >= tiles_x or tile_z >= tiles_z:
            return np.zeros((tile_size, tile_size), dtype=np.uint8)
        return np.array(read_layer_tile(src, layers[layer_name], tile_x, tile_z, tile_size), dtype=np.uint8, copy=True)

    max_half_width = 0
    for region_z in range(regions_z):
        for region_x in range(regions_x):
            if not _region_file(src, layers["rivers"], region_x, region_z).exists():
                continue
            out_tiles = {name: [] for name in layers}
            for local_z in range(region_tiles):
                for local_x in range(region_tiles):
                    tile_x = region_x * region_tiles + local_x
                    tile_z = region_z * region_tiles + local_z
                    if tile_x >= tiles_x or tile_z >= tiles_z:
                        for values in out_tiles.values():
                            values.append(None)
                        continue
                    center_mask = read_tile("rivers", tile_x, tile_z) > 0
                    if not np.any(center_mask):
                        for values in out_tiles.values():
                            values.append(None)
                        continue
                    mask_halo = _halo_stack(read_tile, "rivers", tile_x, tile_z, tiles_x, tiles_z, tile_size, halo) > 0
                    distances = distance_transform_edt(mask_halo)[halo : halo + tile_size, halo : halo + tile_size]
                    old_half = read_tile("half_width", tile_x, tile_z)
                    new_half = np.maximum(1, np.rint(old_half.astype(np.float32) * factor).astype(np.uint8))
                    keep = center_mask & (distances > (old_half.astype(np.float32) - new_half.astype(np.float32)))
                    if not np.any(keep):
                        for values in out_tiles.values():
                            values.append(None)
                        continue
                    river_tile = np.where(keep, 255, 0).astype(np.uint8)
                    half_tile = np.where(keep, new_half, 0).astype(np.uint8)
                    order_tile = np.where(keep, read_tile("order", tile_x, tile_z), 0).astype(np.uint8)
                    old_preview = read_tile("preview_radius", tile_x, tile_z)
                    derived_preview = np.maximum(1, np.rint(half_tile.astype(np.float32) * 0.18).astype(np.uint8))
                    preview_tile = np.where(keep & (old_preview > 0), np.minimum(old_preview, derived_preview), 0).astype(np.uint8)
                    max_half_width = max(max_half_width, int(half_tile.max()))
                    out_tiles["rivers"].append(river_tile)
                    out_tiles["half_width"].append(half_tile)
                    out_tiles["order"].append(order_tile)
                    out_tiles["preview_radius"].append(preview_tile)

            for name, layer in layers.items():
                out_path = _region_file(dst, layer, region_x, region_z)
                write_region_file_from_tiles(
                    out_path,
                    tiles=out_tiles[name],
                    tile_size=tile_size,
                    region_tiles=region_tiles,
                    dtype="uint8",
                )

    rivers["extension"] = u8_extension()
    rivers["max_half_width"] = int(max_half_width)
    rivers["note"] = (
        "255 marks cells inside variable-width river/watercourse vectors. "
        "Existing packed river masks were shrunk to one-third output width for in-world readability."
    )
    rivers.update(region_metadata(rivers["path"], "uint8", region_tiles=region_tiles))
    for path_key, prefix in (
        ("order_path", "order"),
        ("half_width_path", "half_width"),
        ("preview_radius_path", "preview_radius"),
    ):
        for key, value in region_metadata(rivers[path_key], "uint8", region_tiles=region_tiles).items():
            rivers[f"{prefix}_{key}"] = value
    (dst / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"shrunk river regions: max_half_width={max_half_width}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--src", required=True, type=Path)
    parser.add_argument("--dst", required=True, type=Path)
    parser.add_argument("--factor", type=float, default=1.0 / 3.0)
    parser.add_argument("--halo", type=int, default=96)
    args = parser.parse_args()
    shrink_rivers(args.src, args.dst, factor=args.factor, halo=args.halo)


if __name__ == "__main__":
    main()
