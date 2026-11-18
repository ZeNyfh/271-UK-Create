"""Build a Chunky CSV of Minecraft chunks that contain ore-layer pixels."""

from __future__ import annotations

from pathlib import Path
from typing import Any, Iterable
import re

import numpy as np

from .manifest import ORE_NAMES, read_manifest
from .tiles import (
    DEFAULT_REGION_TILES,
    REGION_ENTRY,
    REGION_HEADER,
    REGION_MAGIC,
    REGION_VERSION,
    layer_uses_regions,
    read_layer_tile,
)

# Operator's current full Chunky rectangle (chunks); used only for summary %.
FULL_CHUNKY_RECT = 17_123_424
_REGION_NAME = re.compile(r"^(\d+)_(\d+)\.")


def parse_ore_list(raw: str) -> list[str]:
    names = [part.strip().lower() for part in raw.split(",") if part.strip()]
    if not names:
        raise ValueError("--ores must list at least one ore name")
    return names


def _parse_region_coords(path: Path) -> tuple[int, int]:
    match = _REGION_NAME.match(path.name)
    if not match:
        raise ValueError(f"unrecognised region filename: {path.name}")
    return int(match.group(1)), int(match.group(2))


def _occupied_local_slots(path: Path, region_tiles: int) -> list[tuple[int, int]]:
    """Return (local_x, local_z) for region slots that store a payload."""
    with path.open("rb") as fh:
        header = fh.read(REGION_HEADER.size)
        if len(header) < REGION_HEADER.size:
            return []
        magic, version, _tile_size, stored_region_tiles, _tile_bytes, _default, entry_count = REGION_HEADER.unpack(header)
        if magic != REGION_MAGIC or version != REGION_VERSION:
            raise ValueError(f"{path} is not a supported UKGeo region file")
        if stored_region_tiles != region_tiles:
            raise ValueError(f"{path} region_tiles={stored_region_tiles}, expected {region_tiles}")
        occupied: list[tuple[int, int]] = []
        for index in range(entry_count):
            offset, size = REGION_ENTRY.unpack(fh.read(REGION_ENTRY.size))
            if offset == 0 or size == 0:
                continue
            local_z = index // region_tiles
            local_x = index % region_tiles
            occupied.append((local_x, local_z))
        return occupied


def _chunks_from_tile(
    arr: np.ndarray,
    *,
    min_x: int,
    min_z: int,
    tile_x: int,
    tile_z: int,
    tile_size: int,
    threshold: int,
) -> np.ndarray:
    """Return Nx2 int32 array of unique (chunk_x, chunk_z) for pixels > threshold."""
    ys, xs = np.nonzero(arr > threshold)
    if ys.size == 0:
        return np.empty((0, 2), dtype=np.int32)
    block_x = min_x + tile_x * tile_size + xs.astype(np.int64)
    block_z = min_z + tile_z * tile_size + ys.astype(np.int64)
    pairs = np.column_stack((block_x // 16, block_z // 16)).astype(np.int32, copy=False)
    return np.unique(pairs, axis=0)


def collect_ore_chunks(
    root: Path,
    manifest: dict[str, Any],
    ore_names: Iterable[str],
    *,
    threshold: int = 0,
) -> set[tuple[int, int]]:
    world = manifest["world"]
    min_x = int(world["minecraft_min_x"])
    min_z = int(world["minecraft_min_z"])
    tile_size = int(manifest["tile_size"])
    ore_layers = manifest.get("ore_layers", {})
    chunks: set[tuple[int, int]] = set()

    for name in ore_names:
        if name not in ore_layers:
            raise KeyError(f"ore layer '{name}' not in manifest ore_layers")
        layer = ore_layers[name]
        if not layer_uses_regions(layer):
            raise ValueError(f"ore layer '{name}' is not packed into regions (expected storage=regions)")
        region_path = root / str(layer.get("region_path", f"{layer['path']}/regions"))
        extension = str(layer.get("region_extension", ".u8rg"))
        region_tiles = int(layer.get("region_tiles", DEFAULT_REGION_TILES))
        if not region_path.is_dir():
            continue
        for region_file in sorted(region_path.glob(f"*{extension}")):
            region_x, region_z = _parse_region_coords(region_file)
            for local_x, local_z in _occupied_local_slots(region_file, region_tiles):
                tile_x = region_x * region_tiles + local_x
                tile_z = region_z * region_tiles + local_z
                arr = read_layer_tile(root, layer, tile_x, tile_z, tile_size)
                if not np.any(arr > threshold):
                    continue
                pairs = _chunks_from_tile(
                    arr,
                    min_x=min_x,
                    min_z=min_z,
                    tile_x=tile_x,
                    tile_z=tile_z,
                    tile_size=tile_size,
                    threshold=threshold,
                )
                chunks.update(map(tuple, pairs.tolist()))
    return chunks


def dilate_chebyshev(chunks: set[tuple[int, int]], radius: int) -> set[tuple[int, int]]:
    if radius <= 0 or not chunks:
        return set(chunks)
    out: set[tuple[int, int]] = set()
    for cx, cz in chunks:
        for dz in range(-radius, radius + 1):
            for dx in range(-radius, radius + 1):
                out.add((cx + dx, cz + dz))
    return out


def expand_mca_regions(chunks: set[tuple[int, int]]) -> set[tuple[int, int]]:
    """Expand to every chunk in each 32x32 MCA region that contains an ore chunk."""
    if not chunks:
        return set()
    regions = {(cx >> 5, cz >> 5) for cx, cz in chunks}
    out: set[tuple[int, int]] = set()
    for rx, rz in regions:
        base_x = rx << 5
        base_z = rz << 5
        for dz in range(32):
            for dx in range(32):
                out.add((base_x + dx, base_z + dz))
    return out


def write_chunk_csv(path: Path, chunks: Iterable[tuple[int, int]]) -> int:
    ordered = sorted(chunks)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("".join(f"{cx},{cz}\n" for cx, cz in ordered), encoding="utf-8")
    return len(ordered)


def estimate_gb(chunk_count: int) -> tuple[float, float]:
    """Rough anvil size range at 15-25 KiB/chunk."""
    kib_lo, kib_hi = 15.0, 25.0
    return (
        chunk_count * kib_lo / (1024.0 * 1024.0),
        chunk_count * kib_hi / (1024.0 * 1024.0),
    )


def build_ore_chunk_csv(
    root: Path,
    out: Path,
    *,
    ores: str = ",".join(ORE_NAMES),
    dilate: int = 4,
    threshold: int = 0,
    mca_snap: bool = False,
) -> dict[str, Any]:
    manifest = read_manifest(root / "manifest.json")
    ore_names = parse_ore_list(ores)
    raw = collect_ore_chunks(root, manifest, ore_names, threshold=threshold)
    dilated = dilate_chebyshev(raw, dilate)
    final = expand_mca_regions(dilated) if mca_snap else dilated
    count = write_chunk_csv(out, final)
    gb_lo, gb_hi = estimate_gb(count)
    pct = 100.0 * count / FULL_CHUNKY_RECT if FULL_CHUNKY_RECT else 0.0
    return {
        "out": str(out),
        "ores": ore_names,
        "raw_chunks": len(raw),
        "dilate": dilate,
        "mca_snap": mca_snap,
        "chunk_count": count,
        "pct_of_full_rect": pct,
        "est_gb_lo": gb_lo,
        "est_gb_hi": gb_hi,
        "full_rect": FULL_CHUNKY_RECT,
    }
