import json
from pathlib import Path

import numpy as np

from ukgeo.ore_chunk_csv import build_ore_chunk_csv, dilate_chebyshev
from ukgeo.tiles import region_metadata, write_region_file_from_tiles


def _write_manifest(root: Path, *, tile_size: int = 16, min_x: int = 0, min_z: int = 0) -> None:
    layer = {
        "path": "ores/iron",
        "extension": ".u8",
        "dtype": "uint8",
        **region_metadata("ores/iron", "uint8", region_tiles=1),
    }
    manifest = {
        "format": "uk-raster-tiles-v1",
        "tile_size": tile_size,
        "world": {
            "width": tile_size,
            "depth": tile_size,
            "padded_width": tile_size,
            "padded_depth": tile_size,
            "minecraft_min_x": min_x,
            "minecraft_min_z": min_z,
            "minecraft_max_x": min_x + tile_size - 1,
            "minecraft_max_z": min_z + tile_size - 1,
        },
        "ore_layers": {"iron": layer},
    }
    (root / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")


def test_ore_chunk_csv_dilate_and_lines(tmp_path: Path) -> None:
    tile_size = 16
    _write_manifest(tmp_path, tile_size=tile_size, min_x=0, min_z=0)
    ore = np.zeros((tile_size, tile_size), dtype=np.uint8)
    # pixel (x=2, z=3) → block 2,3 → chunk 0,0
    ore[3, 2] = 200
    region_root = tmp_path / "ores" / "iron" / "regions"
    region_root.mkdir(parents=True)
    write_region_file_from_tiles(
        region_root / "000_000.u8rg",
        tiles=[ore],
        tile_size=tile_size,
        region_tiles=1,
        dtype="uint8",
    )

    out = tmp_path / "ores.csv"
    summary = build_ore_chunk_csv(
        tmp_path,
        out,
        ores="iron",
        dilate=1,
        threshold=0,
    )
    lines = out.read_text(encoding="utf-8").strip().splitlines()
    assert summary["raw_chunks"] == 1
    assert summary["chunk_count"] == 9  # Chebyshev r=1 around (0,0)
    assert lines == [f"{cx},{cz}" for cx in range(-1, 2) for cz in range(-1, 2)]
    assert dilate_chebyshev({(5, 7)}, 0) == {(5, 7)}
