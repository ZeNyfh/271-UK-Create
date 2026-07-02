import json
from pathlib import Path

import numpy as np
from PIL import Image

from hoverpreview_tools.hover_previews import (
    PREVIEW_RIVER_MAX_RADIUS,
    PREVIEW_RIVER_MIN_RADIUS,
    export_hover_previews,
    hover_preview_steps,
    hover_preview_scale,
    _minecraft_origin,
    _river_preview_radii,
    _save_sample_tiles,
    _save_visual_layer,
)
from ukgeo.manifest import default_manifest, write_manifest
from ukgeo.tiles import write_r16_tile

def test_hover_preview_index_origin_metadata_uses_nottingham_zero_zero():
    manifest = {
        "world": {
            "width": 25000,
            "depth": 50000,
            "minecraft_min_x": -17588,
            "minecraft_min_z": -36925,
        },
        "georeferencing": {
            "bng_min_easting": 0,
            "bng_min_northing": 0,
            "bng_max_easting": 650000,
            "bng_max_northing": 1300000,
        },
    }

    origin = _minecraft_origin(manifest)

    assert origin["data_x"] == 17588
    assert origin["data_z"] == 36925
    assert round(origin["bng_easting"]) == 457301
    assert round(origin["bng_northing"]) == 339937


def test_save_visual_layer_writes_tile_metadata(tmp_path):
    image = Image.new("RGB", (300, 260), (12, 34, 56))

    mips = _save_visual_layer(tmp_path, image, "layers/height.png", tile_size=128, workers=1)

    assert mips[0]["tiles"]["size"] == 128
    assert mips[0]["tiles"]["columns"] == 3
    assert mips[0]["tiles"]["rows"] == 3
    assert mips[0]["tiles"]["format"] == "png"
    assert mips[0]["tiles"]["template"] == "tiles/1/layers/height/{x}_{y}.png"
    assert (tmp_path / "tiles" / "1" / "layers" / "height" / "0_0.png").exists()
    assert (tmp_path / "tiles" / "1" / "layers" / "height" / "2_2.png").exists()


def test_save_sample_tiles_metadata_is_lossless_and_explicit(tmp_path):
    image = Image.new("L", (260, 258), 7)

    metadata = _save_sample_tiles(
        tmp_path,
        image,
        "samples/vegetation_u8.png",
        tile_size=128,
        encoding="u8",
        workers=1,
    )

    assert metadata == {
        "size": 128,
        "template": "sample_tiles/vegetation_u8/{x}_{y}.png",
        "columns": 3,
        "rows": 3,
        "encoding": "u8",
        "format": "png",
    }
    assert (tmp_path / "sample_tiles" / "vegetation_u8" / "0_0.png").exists()
    assert (tmp_path / "sample_tiles" / "vegetation_u8" / "2_2.png").exists()


def test_hover_preview_scale_uses_padded_tile_bounds():
    manifest = {
        "tile_size": 512,
        "world": {
            "padded_width": 1025,
            "padded_depth": 2049,
        },
    }

    scale, tiles_x, tiles_z = hover_preview_scale(manifest, max_size=1024)

    assert tiles_x == 3
    assert tiles_z == 5
    assert scale == 3


def test_river_preview_radii_use_half_width_hierarchy():
    river = np.array([[255, 255, 255, 255, 0]], dtype=np.uint8)
    half_width = np.array([[2, 10, 32, 0, 32]], dtype=np.uint8)

    radii = _river_preview_radii(river, half_width, "river_half_width")

    assert radii[0, 0] == PREVIEW_RIVER_MIN_RADIUS
    assert radii[0, 1] > radii[0, 0]
    assert radii[0, 2] == PREVIEW_RIVER_MAX_RADIUS
    assert radii[0, 3] == PREVIEW_RIVER_MIN_RADIUS
    assert radii[0, 4] == 0


def test_hover_preview_steps_include_biome_regions(tmp_path):
    root = tmp_path
    (root / "height").mkdir()
    (root / "vegetation").mkdir()
    (root / "biome_regions").mkdir()
    manifest = {
        "height": {"path": "height"},
        "vegetation": {"path": "vegetation"},
        "biome_regions": {"path": "biome_regions"},
    }

    steps = hover_preview_steps(root, manifest)

    assert "vegetation" in steps
    assert "biome_regions" in steps


def test_generate_script_defaults_to_cop30_without_requiring_osni():
    script = (Path(__file__).resolve().parents[1] / "generate_hover_previews.sh").read_text(encoding="utf-8")
    run_height = script[script.index("run_height() {") : script.index("\nrun_vegetation()")]

    assert 'COP30_ARCHIVE="${COP30_ARCHIVE:-$DATA_DIR/rasters_COP30.tar.gz}"' in script
    assert "add-cop30-height-tiles" in run_height
    assert "USE_LEGACY_OSNI_HEIGHT" in run_height
    assert 'require_file "$COP30_ARCHIVE"' in run_height
    assert 'require_file "$OSNI_DTM_ZIP"' in run_height
    assert run_height.index('require_file "$OSNI_DTM_ZIP"') > run_height.index('if is_truthy "$USE_LEGACY_OSNI_HEIGHT"; then')


def test_export_hover_manifest_preserves_height_overlays_and_manifest_bounds(tmp_path):
    root = tmp_path / "world"
    height_root = root / "height"
    height_root.mkdir(parents=True)
    manifest = default_manifest(
        width=700,
        depth=513,
        tile_size=512,
        minecraft_min_x=-26050,
        minecraft_min_z=-36925,
        bng_min_easting=-220000,
        bng_min_northing=0,
        bng_max_easting=650000,
        bng_max_northing=1300000,
    )
    manifest["height_overlays"] = [
        {
            "source": "Copernicus DEM COP30 GeoTIFF",
            "archive": "rasters_COP30.tar.gz",
            "target": "ireland-iom",
            "smoothing": "light",
            "deterrace": True,
            "protect_mainland_gb": True,
        }
    ]
    write_manifest(root / "manifest.json", manifest)
    for tile_z in range(2):
        for tile_x in range(2):
            write_r16_tile(height_root / f"{tile_x:03d}_{tile_z:03d}.r16", np.full((512, 512), 123, dtype="<i2"))

    out = tmp_path / "hoverpreviews"
    export_hover_previews(root, out, max_size=256, tile_size=128, workers=1)

    hover_manifest = json.loads((out / "hover_manifest.json").read_text(encoding="utf-8"))
    assert hover_manifest["world_width"] == 700
    assert hover_manifest["world_depth"] == 513
    assert hover_manifest["bng_min_easting"] == -220000
    assert hover_manifest["world"]["width"] == 700
    assert hover_manifest["world"]["depth"] == 513
    assert hover_manifest["image_width"] == 256
    assert hover_manifest["image_height"] == 256
    assert hover_manifest["height_overlays"] == manifest["height_overlays"]
