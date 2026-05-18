from __future__ import annotations

from dataclasses import dataclass
import math


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
        width: int = 25_000,
        depth: int = 50_000,
        tile_size: int = 512,
        minecraft_min_x: int = -12_500,
        minecraft_min_z: int = -25_000,
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

