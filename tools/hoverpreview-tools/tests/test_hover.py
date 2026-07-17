import json
from pathlib import Path

import numpy as np
from PIL import Image

from hoverpreview_tools.hover_previews import (
    PREVIEW_RIVER_MAX_RADIUS,
    PREVIEW_RIVER_MIN_RADIUS,
    _categorical_overlay_image,
    export_hover_previews,
    hover_preview_steps,
    hover_preview_scale,
    _minecraft_origin,
    _resample_visual_to_base_size,
    _river_preview_radii,
    _save_sample_tiles,
    _save_visual_layer,
)
from hoverpreview_tools.weather_overlay import build_weather_overlay_grid
from ukgeo.manifest import default_manifest, write_manifest
from ukgeo.tiles import write_r16_tile, write_u8_tile

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


def test_river_preview_radii_accept_precomputed_preview_radius_values():
    river = np.array([[255, 255, 255, 255, 255, 0]], dtype=np.uint8)
    preview_radius = np.array([[0, 1, 2, 3, 6, 6]], dtype=np.uint8)

    radii = _river_preview_radii(river, preview_radius, "river_preview_radius")

    assert radii.tolist() == [[0, 1, 2, 3, 6, 0]]


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


def test_hover_preview_steps_include_animal_habitats(tmp_path):
    root = tmp_path
    (root / "animals" / "habitats" / "minecraft" / "fox").mkdir(parents=True)
    manifest = {
        "height": {"path": "height"},
        "animal_habitats": {
            "entities": {
                "minecraft:fox": {"path": "animals/habitats/minecraft/fox"}
            }
        },
    }

    steps = hover_preview_steps(root, manifest)

    assert "animal:minecraft:fox" in steps


def test_surface_overlay_keeps_default_zero_class_transparent():
    values = np.array([[0, 1]], dtype=np.uint8)
    classes = {
        "0": {"color": "#5f625d"},
        "1": {"color": "#c97f3a"},
    }

    image = _categorical_overlay_image(values, classes, alpha=166, transparent_zero=True)

    assert image.getpixel((0, 0)) == (0, 0, 0, 0)
    assert image.getpixel((1, 0)) == (201, 127, 58, 166)


def test_generate_script_defaults_to_cop30_without_requiring_osni():
    script = (Path(__file__).resolve().parents[1] / "generate_hover_previews.sh").read_text(encoding="utf-8")
    config = (Path(__file__).resolve().parents[1] / "config.yml").read_text(encoding="utf-8")
    run_height = script[script.index("run_height() {") : script.index("\nrun_vegetation()")]
    run_preview = script[script.index("run_preview() {") : script.index("\nrun_clean()")]

    assert 'python3 -m ukgeo.config --config "$CONFIG_FILE" --section generate --format shell' in script
    assert "COP30_ARCHIVE: ../../data/rasters_COP30.tar.gz" in config
    assert "COP30_MINECRAFT_Y_OFFSET: 0" in config
    assert "COP30_DEBUG_TARGET_MASK_GEOTIFF: null" in config
    assert "COP30_DEBUG_LAND_MASK_GEOTIFF: null" in config
    assert "HOVERPREVIEW_DEPLOY_MINIMAL: false" in config
    assert "HOVERPREVIEW_RENDERER: auto" in config
    assert "add-cop30-height-tiles" in run_height
    assert '--minecraft-y-offset "$COP30_MINECRAFT_Y_OFFSET"' in run_height
    assert '--debug-target-mask-geotiff "$COP30_DEBUG_TARGET_MASK_GEOTIFF"' in run_height
    assert '--debug-land-mask-geotiff "$COP30_DEBUG_LAND_MASK_GEOTIFF"' in run_height
    assert "USE_LEGACY_OSNI_HEIGHT" in run_height
    assert 'require_file "$COP30_ARCHIVE"' in run_height
    assert 'require_file "$OSNI_DTM_ZIP"' in run_height
    assert '--deploy-minimal' in run_preview
    assert '--renderer "$HOVERPREVIEW_RENDERER"' in run_preview
    assert 'HOVERPREVIEW_DEPLOY_MINIMAL' in run_preview
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
    export_hover_previews(root, out, max_size=256, tile_size=128, workers=1, weather_overlay=False)

    hover_manifest = json.loads((out / "hover_manifest.json").read_text(encoding="utf-8"))
    assert hover_manifest["world_width"] == 700
    assert hover_manifest["world_depth"] == 513
    assert hover_manifest["bng_min_easting"] == -220000
    assert hover_manifest["world"]["width"] == 700
    assert hover_manifest["world"]["depth"] == 513
    assert hover_manifest["image_width"] == 256
    assert hover_manifest["image_height"] == 256
    assert hover_manifest["height_overlays"] == manifest["height_overlays"]
    assert hover_manifest["content_bounds"]["height"] == {"left": 0, "top": 0, "right": 256, "bottom": 256}
    assert hover_manifest["viewer"]["renderer_preference"] == "auto"


def test_export_hover_previews_includes_animal_layers(tmp_path):
    root = tmp_path / "world"
    height_root = root / "height"
    animal_root = root / "animals" / "habitats" / "minecraft" / "fox"
    height_root.mkdir(parents=True)
    animal_root.mkdir(parents=True)
    manifest = default_manifest(width=512, depth=512, tile_size=512)
    manifest["animal_habitats"] = {
        "entities": {
            "minecraft:fox": {
                "path": "animals/habitats/minecraft/fox",
                "extension": ".u8.gz",
            }
        }
    }
    write_manifest(root / "manifest.json", manifest)
    write_r16_tile(height_root / "000_000.r16", np.full((512, 512), 123, dtype="<i2"))
    write_u8_tile(animal_root / "000_000.u8.gz", np.full((512, 512), 255, dtype=np.uint8), 512)

    out = tmp_path / "hoverpreviews"
    export_hover_previews(root, out, max_size=256, tile_size=128, workers=1, weather_overlay=False)

    hover_manifest = json.loads((out / "hover_manifest.json").read_text(encoding="utf-8"))
    animal_layers = [layer for layer in hover_manifest["layers"] if layer["kind"] == "animal"]
    assert len(animal_layers) == 1
    assert animal_layers[0]["entity_id"] == "minecraft:fox"
    assert animal_layers[0]["label"] == "Fox"


def test_export_hover_previews_deploy_minimal_keeps_only_manifest_and_tile_pyramids(tmp_path):
    root = tmp_path / "world"
    height_root = root / "height"
    height_root.mkdir(parents=True)
    manifest = default_manifest(width=512, depth=512, tile_size=512)
    write_manifest(root / "manifest.json", manifest)
    write_r16_tile(height_root / "000_000.r16", np.full((512, 512), 123, dtype="<i2"))

    out = tmp_path / "hoverpreviews"
    export_hover_previews(root, out, max_size=256, tile_size=128, workers=1, deploy_minimal=True, weather_overlay=False)

    assert (out / "hover_manifest.json").exists()
    assert (out / "tiles").exists()
    assert (out / "sample_tiles").exists()
    assert not (out / "layers").exists()
    assert not (out / "mips").exists()
    assert not (out / "samples").exists()

    hover_manifest = json.loads((out / "hover_manifest.json").read_text(encoding="utf-8"))
    assert hover_manifest["generation"]["deploy_minimal"] is True


def test_export_hover_previews_normalises_renderer_preference(tmp_path):
    root = tmp_path / "world"
    height_root = root / "height"
    height_root.mkdir(parents=True)
    manifest = default_manifest(width=512, depth=512, tile_size=512)
    write_manifest(root / "manifest.json", manifest)
    write_r16_tile(height_root / "000_000.r16", np.full((512, 512), 123, dtype="<i2"))

    out = tmp_path / "hoverpreviews"
    export_hover_previews(root, out, max_size=256, tile_size=128, workers=1, renderer="canvas", weather_overlay=False)

    hover_manifest = json.loads((out / "hover_manifest.json").read_text(encoding="utf-8"))
    assert hover_manifest["viewer"]["renderer_preference"] == "2d"
    assert hover_manifest["generation"]["renderer"] == "2d"


def test_resample_visual_to_base_size_scales_visual_overlay():
    image = Image.new("RGBA", (16, 16), (255, 0, 0, 150))

    scaled = _resample_visual_to_base_size(image, (64, 32), resampling=Image.Resampling.BILINEAR)

    assert scaled.size == (64, 32)


def test_weather_overlay_grid_uses_manifest_georeferencing():
    manifest = default_manifest(
        width=700,
        depth=1400,
        tile_size=512,
        minecraft_min_x=-26050,
        minecraft_min_z=-36925,
        bng_min_easting=-220000,
        bng_min_northing=0,
        bng_max_easting=650000,
        bng_max_northing=1300000,
    )

    grid = build_weather_overlay_grid(manifest, grid_columns=8)

    assert grid.columns == 8
    assert grid.rows == 16
    assert len(grid.latitudes) == 128
    assert len(grid.longitudes) == 128
    assert min(grid.longitudes) < max(grid.longitudes)
    assert min(grid.latitudes) < max(grid.latitudes)


def test_export_hover_previews_writes_live_weather_query_metadata(tmp_path):
    root = tmp_path / "world"
    height_root = root / "height"
    height_root.mkdir(parents=True)
    manifest = default_manifest(
        width=512,
        depth=512,
        tile_size=512,
        bng_min_easting=-220000,
        bng_min_northing=0,
        bng_max_easting=650000,
        bng_max_northing=1300000,
    )
    write_manifest(root / "manifest.json", manifest)
    write_r16_tile(height_root / "000_000.r16", np.full((512, 512), 123, dtype="<i2"))

    out = tmp_path / "hoverpreviews"
    export_hover_previews(
        root,
        out,
        max_size=256,
        tile_size=128,
        workers=1,
        weather_overlay=True,
    )

    hover_manifest = json.loads((out / "hover_manifest.json").read_text(encoding="utf-8"))
    assert "live_weather" in hover_manifest
    assert hover_manifest["live_weather"]["provider"] == "Open-Meteo"
    assert hover_manifest["live_weather"]["metrics"]["cloud_cover"]["unit"] == "percent"
    assert hover_manifest["live_weather"]["metrics"]["downfall_coverage"]["source"] == "hourly.precipitation_probability[0]"
    assert hover_manifest["live_weather"]["grid"]["rows"] >= 2
    assert hover_manifest["live_weather"]["grid"]["columns"] >= 2
    assert len(hover_manifest["live_weather"]["grid"]["latitudes"]) == (
        hover_manifest["live_weather"]["grid"]["rows"] * hover_manifest["live_weather"]["grid"]["columns"]
    )
    assert [layer for layer in hover_manifest["layers"] if layer["kind"] == "weather"] == []
