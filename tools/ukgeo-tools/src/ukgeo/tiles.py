from __future__ import annotations

from pathlib import Path
import gzip
import math
import struct
import zlib
from typing import Any, Iterable
import numpy as np

from .config import get_config_value
from .coords import tile_filename


HEIGHT_NODATA = -32768
REGION_MAGIC = b"UKRG"
REGION_VERSION = 1
DEFAULT_REGION_TILES = 8
REGION_HEADER = struct.Struct("<4sIIIIiI")
REGION_ENTRY = struct.Struct("<QI")


def tile_compression() -> str:
    """Return the output tile compression mode for newly generated tiles.

    The runtime mod reads both compressed and uncompressed tiles.  New datasets
    default to uncompressed files because Minecraft performs many small random
    tile reads during chunk generation, where gzip decompression CPU cost tends
    to be more expensive than the extra disk space.
    """
    config_path = Path(__file__).resolve().parents[2] / "config.yml"
    value = str(get_config_value(config_path, "runtime.UKGEO_TILE_COMPRESSION", "none")).strip().lower()
    if value in {"", "none", "raw", "uncompressed", "off", "0", "false"}:
        return "none"
    if value in {"gzip", "gz", "compressed", "on", "1", "true"}:
        return "gzip"
    raise ValueError("runtime.UKGEO_TILE_COMPRESSION in config.yml must be 'none' or 'gzip'")


def r16_extension(compression: str | None = None) -> str:
    mode = tile_compression() if compression is None else compression
    return ".r16.gz" if mode == "gzip" else ".r16"


def u8_extension(compression: str | None = None) -> str:
    mode = tile_compression() if compression is None else compression
    return ".u8.gz" if mode == "gzip" else ".u8"


def is_gzip_path(path: Path) -> bool:
    return path.name.lower().endswith(".gz")


def gzip_alternate(path: Path) -> Path:
    name = path.name
    if name.lower().endswith(".gz"):
        return path.with_name(name[:-3])
    return path.with_name(name + ".gz")


def resolve_existing_tile(path: Path) -> Path:
    if path.exists():
        return path
    alt = gzip_alternate(path)
    if alt.exists():
        return alt
    return path


def _read_tile_bytes(path: Path, expected: int) -> bytes:
    resolved = resolve_existing_tile(path)
    if is_gzip_path(resolved):
        with gzip.open(resolved, "rb") as fh:
            data = fh.read()
        size_label = "decompressed"
    else:
        data = resolved.read_bytes()
        size_label = "raw"
    if len(data) != expected:
        raise ValueError(f"{resolved} {size_label} size is {len(data)} bytes, expected {expected}")
    return data


def write_r16_tile(path: Path, array: np.ndarray) -> None:
    arr = np.asarray(array, dtype="<i2")
    if arr.shape != (512, 512):
        raise ValueError(f"height tile must be 512x512, got {arr.shape}")
    if np.all(arr == HEIGHT_NODATA):
        path.unlink(missing_ok=True)
        gzip_alternate(path).unlink(missing_ok=True)
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    data = arr.tobytes(order="C")
    if is_gzip_path(path):
        with gzip.open(path, "wb", compresslevel=1) as fh:
            fh.write(data)
    else:
        path.write_bytes(data)


def read_r16_tile(path: Path, tile_size: int = 512) -> np.ndarray:
    if not resolve_existing_tile(path).exists():
        return np.full((tile_size, tile_size), HEIGHT_NODATA, dtype="<i2")
    data = _read_tile_bytes(path, tile_size * tile_size * 2)
    return np.frombuffer(data, dtype="<i2").reshape((tile_size, tile_size))


def write_u8_tile(path: Path, array: np.ndarray, tile_size: int = 512) -> None:
    arr = np.asarray(array, dtype=np.uint8)
    if arr.shape != (tile_size, tile_size):
        raise ValueError(f"uint8 tile must be {tile_size}x{tile_size}, got {arr.shape}")
    if not np.any(arr):
        path.unlink(missing_ok=True)
        gzip_alternate(path).unlink(missing_ok=True)
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    data = arr.tobytes(order="C")
    if is_gzip_path(path):
        with gzip.open(path, "wb", compresslevel=1) as fh:
            fh.write(data)
    else:
        path.write_bytes(data)


def read_u8_tile(path: Path, tile_size: int = 512) -> np.ndarray:
    if not resolve_existing_tile(path).exists():
        return np.zeros((tile_size, tile_size), dtype=np.uint8)
    data = _read_tile_bytes(path, tile_size * tile_size)
    return np.frombuffer(data, dtype=np.uint8).reshape((tile_size, tile_size))


def read_r16_tile_or_nodata(path: Path, tile_size: int = 512) -> np.ndarray:
    return read_r16_tile(path, tile_size)


def tile_path(root: Path, tile_x: int, tile_z: int, extension: str) -> Path:
    return root / tile_filename(tile_x, tile_z, extension)


def region_extension(dtype: str) -> str:
    if dtype == "uint8":
        return ".u8rg"
    if dtype == "int16_le":
        return ".r16rg"
    raise ValueError(f"Unsupported packed region dtype: {dtype}")


def default_tile_value(dtype: str) -> int:
    return 0 if dtype == "uint8" else HEIGHT_NODATA


def raw_tile_bytes(dtype: str, tile_size: int) -> int:
    return tile_size * tile_size * (2 if dtype == "int16_le" else 1)


def tile_dtype(dtype: str) -> np.dtype:
    return np.dtype("<i2") if dtype == "int16_le" else np.dtype(np.uint8)


def region_metadata(layer_path: str, dtype: str, *, region_tiles: int = DEFAULT_REGION_TILES) -> dict[str, Any]:
    return {
        "storage": "regions",
        "region_path": f"{layer_path}/regions",
        "region_extension": region_extension(dtype),
        "region_tiles": int(region_tiles),
        "missing_tile": default_tile_value(dtype),
    }


def layer_uses_regions(layer: dict[str, Any]) -> bool:
    return str(layer.get("storage", "tiles")).lower() == "regions"


def river_u8_layer(rivers: dict[str, Any], path_key: str = "path", prefix: str | None = None) -> dict[str, Any]:
    layer: dict[str, Any] = {
        "path": rivers[path_key],
        "extension": rivers.get("extension", u8_extension()),
        "dtype": "uint8",
    }
    if prefix is None:
        for key in ("storage", "region_path", "region_extension", "region_tiles", "missing_tile"):
            if key in rivers:
                layer[key] = rivers[key]
    else:
        for key in ("storage", "region_path", "region_extension", "region_tiles", "missing_tile"):
            prefixed = f"{prefix}_{key}"
            if prefixed in rivers:
                layer[key] = rivers[prefixed]
    return layer


def read_layer_tile(root: Path, layer: dict[str, Any], tile_x: int, tile_z: int, tile_size: int) -> np.ndarray:
    dtype = str(layer.get("dtype", "uint8"))
    if layer_uses_regions(layer):
        return read_region_tile(root, layer, tile_x, tile_z, tile_size)
    extension = str(layer.get("extension", r16_extension() if dtype == "int16_le" else u8_extension()))
    path = root / str(layer["path"]) / tile_filename(tile_x, tile_z, extension)
    return read_r16_tile_or_nodata(path, tile_size) if dtype == "int16_le" else read_u8_tile(path, tile_size)


def read_region_tile(root: Path, layer: dict[str, Any], tile_x: int, tile_z: int, tile_size: int) -> np.ndarray:
    dtype = str(layer.get("dtype", "uint8"))
    np_dtype = tile_dtype(dtype)
    default = int(layer.get("missing_tile", default_tile_value(dtype)))
    region_tiles = int(layer.get("region_tiles", DEFAULT_REGION_TILES))
    region_path = str(layer.get("region_path", f"{layer['path']}/regions"))
    extension = str(layer.get("region_extension", region_extension(dtype)))
    region_x = tile_x // region_tiles
    region_z = tile_z // region_tiles
    index = (tile_z % region_tiles) * region_tiles + (tile_x % region_tiles)
    path = root / region_path / tile_filename(region_x, region_z, extension)
    if not path.exists():
        return np.full((tile_size, tile_size), default, dtype=np_dtype)
    with path.open("rb") as fh:
        header = fh.read(REGION_HEADER.size)
        magic, version, stored_tile_size, stored_region_tiles, tile_bytes, stored_default, entry_count = REGION_HEADER.unpack(header)
        if magic != REGION_MAGIC or version != REGION_VERSION:
            raise ValueError(f"{path} is not a supported UKGeo region file")
        if stored_tile_size != tile_size or stored_region_tiles != region_tiles:
            raise ValueError(f"{path} region geometry does not match manifest")
        if index >= entry_count:
            return np.full((tile_size, tile_size), stored_default, dtype=np_dtype)
        fh.seek(REGION_HEADER.size + index * REGION_ENTRY.size)
        offset, size = REGION_ENTRY.unpack(fh.read(REGION_ENTRY.size))
        if offset == 0 or size == 0:
            return np.full((tile_size, tile_size), stored_default, dtype=np_dtype)
        fh.seek(offset)
        data = fh.read(size)
    if size != tile_bytes:
        data = zlib.decompress(data)
        if len(data) != tile_bytes:
            raise ValueError(f"{path} decompressed tile payload was {len(data)} bytes, expected {tile_bytes}")
    return np.frombuffer(data, dtype=np_dtype).reshape((tile_size, tile_size))


def pack_manifest_regions(root: Path, manifest: dict[str, Any], *, region_tiles: int = DEFAULT_REGION_TILES, delete_raw: bool = True) -> dict[str, Any]:
    pack_layer_regions(root, manifest["height"], manifest, region_tiles=region_tiles, delete_raw=delete_raw)
    for key in ("surface_geology", "vegetation", "biome_regions"):
        if key in manifest:
            pack_layer_regions(root, manifest[key], manifest, region_tiles=region_tiles, delete_raw=delete_raw)
    rivers = manifest.get("rivers")
    if rivers:
        main = river_u8_layer(rivers)
        pack_layer_regions(root, main, manifest, region_tiles=region_tiles, delete_raw=delete_raw)
        _copy_layer_region_metadata(main, rivers)
        for path_key, prefix in (
            ("order_path", "order"),
            ("half_width_path", "half_width"),
            ("preview_radius_path", "preview_radius"),
        ):
            if path_key in rivers:
                aux = river_u8_layer(rivers, path_key, prefix)
                pack_layer_regions(root, aux, manifest, region_tiles=region_tiles, delete_raw=delete_raw)
                _copy_layer_region_metadata(aux, rivers, prefix=prefix)
    for layer in manifest.get("ore_layers", {}).values():
        pack_layer_regions(root, layer, manifest, region_tiles=region_tiles, delete_raw=delete_raw)
    for layer in manifest.get("animal_habitats", {}).get("entities", {}).values():
        pack_layer_regions(root, layer, manifest, region_tiles=region_tiles, delete_raw=delete_raw)
    manifest["tile_storage"] = {
        "format": "ukgeo-region-tiles-v1",
        "region_tiles": int(region_tiles),
        "sparse_zero_u8": True,
        "missing_height": "nodata",
    }
    return manifest


def _copy_layer_region_metadata(layer: dict[str, Any], target: dict[str, Any], prefix: str | None = None) -> None:
    for key in ("storage", "region_path", "region_extension", "region_tiles", "missing_tile"):
        if key in layer:
            target[f"{prefix}_{key}" if prefix else key] = layer[key]


def iter_packable_layers(manifest: dict[str, Any]) -> Iterable[dict[str, Any]]:
    yield manifest["height"]
    for key in ("surface_geology", "vegetation", "biome_regions"):
        if key in manifest:
            yield manifest[key]
    rivers = manifest.get("rivers")
    if rivers:
        yield {"path": rivers["path"], "extension": rivers.get("extension", u8_extension()), "dtype": "uint8"}
        for key in ("order_path", "half_width_path", "preview_radius_path"):
            if key in rivers:
                yield {"path": rivers[key], "extension": rivers.get("extension", u8_extension()), "dtype": "uint8"}
    yield from manifest.get("ore_layers", {}).values()
    for layer in manifest.get("animal_habitats", {}).get("entities", {}).values():
        yield layer


def pack_layer_regions(root: Path, layer: dict[str, Any], manifest: dict[str, Any], *, region_tiles: int, delete_raw: bool) -> None:
    dtype = str(layer.get("dtype", "uint8"))
    tile_size = int(manifest["tile_size"])
    cell_blocks = int(layer.get("cell_blocks", 1))
    width = int(layer.get("width_cells", math.ceil(int(manifest["world"]["padded_width"]) / cell_blocks)))
    depth = int(layer.get("depth_cells", math.ceil(int(manifest["world"]["padded_depth"]) / cell_blocks)))
    tiles_x = math.ceil(width / tile_size)
    tiles_z = math.ceil(depth / tile_size)
    extension = str(layer.get("extension", r16_extension() if dtype == "int16_le" else u8_extension()))
    layer_path = str(layer["path"])
    raw_root = root / layer_path
    region_root = root / layer_path / "regions"
    source_layer = {**layer, "extension": extension, "dtype": dtype}
    if _has_raw_tiles(raw_root, extension):
        source_layer["storage"] = "tiles"
    region_root.mkdir(parents=True, exist_ok=True)
    for region_z in range(math.ceil(tiles_z / region_tiles)):
        for region_x in range(math.ceil(tiles_x / region_tiles)):
            _write_region_file(
                root,
                source_layer,
                raw_root,
                region_root / tile_filename(region_x, region_z, region_extension(dtype)),
                region_x,
                region_z,
                tiles_x,
                tiles_z,
                tile_size,
                region_tiles,
                dtype,
                extension,
            )
    layer.update(region_metadata(layer_path, dtype, region_tiles=region_tiles))
    if delete_raw and raw_root.exists():
        for tile in raw_root.glob(f"*{extension}"):
            tile.unlink(missing_ok=True)
        for tile in raw_root.glob(f"*{extension}.gz"):
            tile.unlink(missing_ok=True)


def _has_raw_tiles(raw_root: Path, extension: str) -> bool:
    if not raw_root.exists():
        return False
    return any(raw_root.glob(f"*{extension}")) or any(raw_root.glob(f"*{extension}.gz"))


def _write_region_file(
    root: Path,
    layer: dict[str, Any],
    raw_root: Path,
    out: Path,
    region_x: int,
    region_z: int,
    tiles_x: int,
    tiles_z: int,
    tile_size: int,
    region_tiles: int,
    dtype: str,
    extension: str,
) -> None:
    tile_bytes = raw_tile_bytes(dtype, tile_size)
    default = default_tile_value(dtype)
    entry_count = region_tiles * region_tiles
    payloads: list[bytes | None] = []
    for local_z in range(region_tiles):
        for local_x in range(region_tiles):
            tile_x = region_x * region_tiles + local_x
            tile_z = region_z * region_tiles + local_z
            if tile_x >= tiles_x or tile_z >= tiles_z:
                payloads.append(None)
                continue
            tile = read_layer_tile(root, {**layer, "extension": extension, "dtype": dtype}, tile_x, tile_z, tile_size)
            if np.all(tile == default):
                payloads.append(None)
            else:
                raw = np.asarray(tile, dtype=tile_dtype(dtype)).tobytes(order="C")
                compressed = zlib.compress(raw, level=6)
                payloads.append(compressed if len(compressed) < len(raw) else raw)
    if not any(payloads):
        out.unlink(missing_ok=True)
        return
    out.parent.mkdir(parents=True, exist_ok=True)
    offset = REGION_HEADER.size + entry_count * REGION_ENTRY.size
    entries: list[tuple[int, int]] = []
    body = bytearray()
    for payload in payloads:
        if payload is None:
            entries.append((0, 0))
            continue
        entries.append((offset + len(body), len(payload)))
        body.extend(payload)
    with out.open("wb") as fh:
        fh.write(REGION_HEADER.pack(REGION_MAGIC, REGION_VERSION, tile_size, region_tiles, tile_bytes, default, entry_count))
        for entry in entries:
            fh.write(REGION_ENTRY.pack(*entry))
        fh.write(body)
