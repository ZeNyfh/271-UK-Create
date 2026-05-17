# ukgeo-tools

Python 3.11+ preprocessing tools for UK Geological Create Worldgen.

## Install

```bash
cd tools/ukgeo-tools
python3 -m venv .venv
.venv/bin/python -m pip install -e ".[test]"
.venv/bin/pytest
```

## Inspect inputs

```bash
.venv/bin/ukgeo inspect-os ../../data/terr50_gagg_gb.zip
.venv/bin/ukgeo inspect-bgs ../../data/BGS_Geology_50k_GeoPackage_SAMPLE.zip
```

## Generate a small test area

Choose a British National Grid extent, then run with a smaller Minecraft grid:

```bash
.venv/bin/ukgeo make-height-tiles \
  --os-zip ../../data/terr50_gagg_gb.zip \
  --out ./uk_world_data \
  --bng-min-easting 430000 --bng-min-northing 110000 \
  --bng-max-easting 440000 --bng-max-northing 130000 \
  --world-width 1024 --world-depth 2048

.venv/bin/ukgeo make-ore-tiles \
  --bgs ../../data/BGS_Geology_50k_GeoPackage_SAMPLE.zip \
  --rules examples/ore_rules.yml \
  --manifest ./uk_world_data/manifest.json \
  --out ./uk_world_data
```

For the national BGS 625k GeoPackage, use the 625k rules. These include the normal ore layers plus geology block layers such as granite, limestone, calcite, tuff, and optional Create stone types:

```bash
.venv/bin/ukgeo make-ore-tiles \
  --bgs ../../data/BGS_Geology_625k_bedrock_gpkg.zip \
  --rules examples/ore_rules_625k.yml \
  --manifest ./uk_world_data/manifest.json \
  --out ./uk_world_data
```

Generate a categorical surface geology skin from the same 625k data:

```bash
.venv/bin/ukgeo make-surface-geology-tiles \
  --bgs ../../data/BGS_Geology_625k_bedrock_gpkg.zip \
  --rules examples/surface_geology_625k.yml \
  --manifest ./uk_world_data/manifest.json \
  --out ./uk_world_data
```

Merge Northern Ireland OSNI 50m DTM into an existing GB height dataset:

```bash
.venv/bin/ukgeo add-osni-height-tiles \
  --osni-dtm ../../data/osni_opendata_50m_dtm.zip \
  --manifest ./uk_world_data/manifest.json \
  --out ./uk_world_data \
  --resampling bilinear
```

Generate GB river mask tiles from OS Open Rivers:

```bash
.venv/bin/ukgeo make-river-tiles \
  --rivers ../../data/oprvrs_gpkg_gb.zip \
  --manifest ./uk_world_data/manifest.json \
  --out ./uk_world_data \
  --width-metres 220
```

## Generate the default 25k x 50k world

Use the same commands without overriding `--world-width` or `--world-depth`.
The first height implementation uses a guarded in-memory output mosaic; for low-memory machines, generate smaller extents or replace the height path with a windowed VRT workflow.

## Validate, preview, sample

```bash
.venv/bin/ukgeo validate-tiles ./uk_world_data
.venv/bin/ukgeo stats ./uk_world_data
.venv/bin/ukgeo preview ./uk_world_data --layer height --out preview.png
.venv/bin/ukgeo preview ./uk_world_data --layer surface --out surface_geology.png
.venv/bin/ukgeo preview ./uk_world_data --layer rivers --out rivers.png
.venv/bin/ukgeo preview ./uk_world_data --layer ore:zinc --out zinc.png
.venv/bin/ukgeo preview ./uk_world_data --layer ore:coal --style overlay --max-size 12000 --out coal_on_height.png
.venv/bin/ukgeo preview ./uk_world_data --layer ores --max-size 12000 --out ores_all.png
.venv/bin/ukgeo preview ./uk_world_data --layer ore:granite --out granite.png
.venv/bin/ukgeo preview ./uk_world_data --layer height --style gray --out height_gray.png
.venv/bin/ukgeo preview ./uk_world_data --layer height --max-size 12000 --out height_large.png
.venv/bin/ukgeo hover-map ./uk_world_data --max-size 12000
.venv/bin/ukgeo sample ./uk_world_data --x 0 --z 0
```

The preview PNGs show the whole generated map extent, downscaled so the longest side is `--max-size` pixels. Use a larger `--max-size` for a more detailed whole-map image. `--max-size 0` writes native tile resolution, but the default 25k x 50k world is a very large image and can require several GB of RAM.

`hover-map` opens an interactive GUI heightmap. Mouse wheel zooms around the cursor, middle/right drag pans, and hovering shows Minecraft `x/z`, height, tile/cell, and British National Grid easting/northing. Left click copies the Minecraft `x z` pair to the clipboard.

Copy the finished `uk_world_data` directory to the Minecraft client/server root, or point the mod config at another directory.
