from pathlib import Path

import numpy as np
from PIL import Image

from ukgeo.manifest import default_manifest, write_manifest
from ukgeo.ore_image_overlay import _overlay_placement, _read_overlay_mask, apply_ore_image_overlay
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


def test_apply_ore_image_overlay_reads_svg_paths(tmp_path: Path):
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

    image_path = tmp_path / "iron_overlay.svg"
    image_path.write_text(
        '<svg xmlns="http://www.w3.org/2000/svg" width="4" height="4" viewBox="0 0 4 4">'
        '<path d="M 0 0 L 2 0 L 2 2 L 0 2 Z" fill="#e6392b"/>'
        "</svg>",
        encoding="utf-8",
    )

    apply_ore_image_overlay(image=image_path, manifest_path=manifest_path, out=root, ore="iron", score=177)

    tile = read_u8_tile(root / "ores" / "iron" / "000_000.u8.gz")
    assert int(tile[:256, :256].max()) == 177
    assert int(tile[384:, 384:].max()) == 0


def test_overlay_placement_preserves_source_aspect_ratio():
    scale_x, scale_z, offset_x, offset_z, placement = _overlay_placement(900, 1044, 650_000, 1_300_000, "cover")

    assert scale_x == 1_300_000 / 1044
    assert scale_z == scale_x
    assert offset_x < 0
    assert offset_z == 0
    assert placement["mode"] == "cover"


def test_overlay_placement_registers_blue_outline_to_target_bbox(tmp_path: Path):
    image_path = tmp_path / "iron_overlay.svg"
    image_path.write_text(
        '<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10" viewBox="0 0 10 10">'
        '<path d="M 2 2 L 8 2 L 8 8 L 2 8 Z" fill="none" stroke="#0000ff"/>'
        '<path d="M 4 4 L 6 4 L 6 6 L 4 6 Z" fill="#e6392b"/>'
        "</svg>",
        encoding="utf-8",
    )
    mask, source_bbox = _read_overlay_mask(image_path, red_min=180, green_max=120, blue_max=120)

    assert mask.any()
    assert source_bbox == (2, 2, 8, 8)
    scale_x, scale_z, offset_x, offset_z, placement = _overlay_placement(
        mask.shape[1],
        mask.shape[0],
        100,
        200,
        "outline",
        source_bbox=source_bbox,
        target_bbox=(10, 20, 80, 160),
    )
    assert scale_x == 71 / 7
    assert scale_z == 141 / 7
    assert offset_x == 10 - 2 * scale_x
    assert offset_z == 20 - 2 * scale_z
    assert placement["mode"] == "outline"
