from __future__ import annotations

from dataclasses import dataclass
import math


NOTTINGHAM_ORIGIN_BNG_EASTING = 457_306.0
NOTTINGHAM_ORIGIN_BNG_NORTHING = 339_945.0
DEFAULT_BNG_MIN_EASTING = 0.0
DEFAULT_BNG_MIN_NORTHING = 0.0
DEFAULT_BNG_MAX_EASTING = 650_000.0
DEFAULT_BNG_MAX_NORTHING = 1_300_000.0
DEFAULT_WORLD_WIDTH = 25_000
DEFAULT_WORLD_DEPTH = 50_000
DEFAULT_TILE_SIZE = 512


def minecraft_min_for_bng_origin(
    *,
    bng_easting: float,
    bng_northing: float,
    bng_min_easting: float = DEFAULT_BNG_MIN_EASTING,
    bng_min_northing: float = DEFAULT_BNG_MIN_NORTHING,
    bng_max_easting: float = DEFAULT_BNG_MAX_EASTING,
    bng_max_northing: float = DEFAULT_BNG_MAX_NORTHING,
    world_width: int = DEFAULT_WORLD_WIDTH,
    world_depth: int = DEFAULT_WORLD_DEPTH,
) -> tuple[int, int]:
    x_scale = (bng_max_easting - bng_min_easting) / world_width
    z_scale = (bng_max_northing - bng_min_northing) / world_depth
    data_x = round((bng_easting - bng_min_easting) / x_scale - 0.5)
    data_z = round((bng_max_northing - bng_northing) / z_scale - 0.5)
    return -data_x, -data_z


DEFAULT_MINECRAFT_MIN_X, DEFAULT_MINECRAFT_MIN_Z = minecraft_min_for_bng_origin(
    bng_easting=NOTTINGHAM_ORIGIN_BNG_EASTING,
    bng_northing=NOTTINGHAM_ORIGIN_BNG_NORTHING,
)


@dataclass(frozen=True)
class WorldBounds:
    width: int
    depth: int
    padded_width: int
    padded_depth: int
    minecraft_min_x: int
    minecraft_min_z: int
    minecraft_max_x: int
    minecraft_max_z: int
    tile_size: int = 512

    @classmethod
    def from_dimensions(
        cls,
        width: int = DEFAULT_WORLD_WIDTH,
        depth: int = DEFAULT_WORLD_DEPTH,
        tile_size: int = DEFAULT_TILE_SIZE,
        minecraft_min_x: int = DEFAULT_MINECRAFT_MIN_X,
        minecraft_min_z: int = DEFAULT_MINECRAFT_MIN_Z,
    ) -> "WorldBounds":
        padded_width = math.ceil(width / tile_size) * tile_size
        padded_depth = math.ceil(depth / tile_size) * tile_size
        return cls(
            width=width,
            depth=depth,
            padded_width=padded_width,
            padded_depth=padded_depth,
            minecraft_min_x=minecraft_min_x,
            minecraft_min_z=minecraft_min_z,
            minecraft_max_x=minecraft_min_x + width - 1,
            minecraft_max_z=minecraft_min_z + depth - 1,
            tile_size=tile_size,
        )


def tile_filename(tile_x: int, tile_z: int, extension: str) -> str:
    return f"{tile_x:03d}_{tile_z:03d}{extension}"


def minecraft_to_tile_cell(x: int, z: int, bounds: WorldBounds) -> tuple[int, int, int, int]:
    return minecraft_to_layer_cell(x, z, bounds, cell_blocks=1)


def minecraft_to_layer_cell(x: int, z: int, bounds: WorldBounds, cell_blocks: int = 1) -> tuple[int, int, int, int]:
    blocks = max(1, cell_blocks)
    data_x = x - bounds.minecraft_min_x
    data_z = z - bounds.minecraft_min_z
    if data_x < 0 or data_z < 0 or data_x >= bounds.padded_width or data_z >= bounds.padded_depth:
        raise ValueError(f"Minecraft coordinate {x},{z} is outside padded data bounds")
    data_x //= blocks
    data_z //= blocks
    tile_x = data_x // bounds.tile_size
    tile_z = data_z // bounds.tile_size
    local_x = data_x % bounds.tile_size
    local_z = data_z % bounds.tile_size
    return tile_x, tile_z, local_x, local_z
