from pathlib import Path

import numpy as np
from PIL import Image

from ukgeo.manifest import default_manifest, write_manifest
from ukgeo.ore_image_overlay import apply_ore_image_overlay
from ukgeo.tiles import read_u8_tile


def test_apply_ore_image_overlay_merges_red_pixels_into_iron_tiles(tmp_path: Path):
    root = tmp_path / "world"
    manifest_path = root / "manifest.json"
    manifest = default_manifest(
        width=512,
        depth=512,
        tile_size=512,
        bng_min_easting=0,
        bng_min_northing=0,
        bng_max_easting=650000,
        bng_max_northing=1300000,
    )
    write_manifest(manifest_path, manifest)

    image_path = tmp_path / "iron_overlay.png"
    pixels = np.full((4, 4, 3), 240, dtype=np.uint8)
    pixels[:2, :2] = (220, 20, 20)
    Image.fromarray(pixels, mode="RGB").save(image_path)

    apply_ore_image_overlay(image=image_path, manifest_path=manifest_path, out=root, ore="iron", score=199)

    tile = read_u8_tile(root / "ores" / "iron" / "000_000.u8.gz")
    assert int(tile[:256, :256].max()) == 199
    assert int(tile[256:, 256:].max()) == 0
