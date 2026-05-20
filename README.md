# 271 UK Create

This repository contains two related projects:

1. `tools/ukgeo-tools`: Python preprocessing tools for OS Terrain 50 ASCII Grid and BGS GeoPackage geology.
2. `mods/ukgeo`: a NeoForge 1.21.1 mod that reads simplified runtime raster tiles on demand.

The Minecraft runtime reads only `.r16.gz` height tiles, `.u8.gz` score/class tiles, and `manifest.json`. It does not parse GeoPackage, Shapefile, GeoTIFF, or OS ASCII Grid files.

## Pipeline

1. Download OS Terrain 50 ASCII Grid data, for example `terr50_gagg_gb.zip`.
2. Download BGS Geology 50K GeoPackage data.
3. Inspect the inputs:

```bash
cd tools/ukgeo-tools
python3 -m venv .venv
.venv/bin/python -m pip install -e ".[test]"
.venv/bin/ukgeo inspect-os ../../data/terr50_gagg_gb.zip
.venv/bin/ukgeo inspect-bgs ../../data/BGS_Geology_50k_GeoPackage_SAMPLE.zip
```

4. Choose a British National Grid extent.
5. Generate height and ore tiles:

```bash
.venv/bin/ukgeo make-height-tiles --os-zip ../../data/terr50_gagg_gb.zip --out ./uk_world_data \
  --bng-min-easting 430000 --bng-min-northing 110000 \
  --bng-max-easting 440000 --bng-max-northing 130000 \
  --world-width 1024 --world-depth 2048

.venv/bin/ukgeo make-ore-tiles --bgs ../../data/BGS_Geology_50k_GeoPackage_SAMPLE.zip \
  --rules examples/ore_rules.yml --manifest ./uk_world_data/manifest.json --out ./uk_world_data
```

For the full BGS 625k GeoPackage, use:

```bash
.venv/bin/ukgeo make-ore-tiles --bgs ../../data/BGS_Geology_625k_bedrock_gpkg.zip \
  --rules examples/ore_rules_625k.yml --manifest ./uk_world_data/manifest.json --out ./uk_world_data --jobs 4

.venv/bin/ukgeo make-surface-geology-tiles --bgs ../../data/BGS_Geology_625k_bedrock_gpkg.zip \
  --rules examples/surface_geology_625k.yml --manifest ./uk_world_data/manifest.json --out ./uk_world_data

.venv/bin/ukgeo add-osni-height-tiles --osni-dtm ../../data/osni_opendata_50m_dtm.zip \
  --manifest ./uk_world_data/manifest.json --out ./uk_world_data --resampling bilinear

.venv/bin/ukgeo make-river-tiles --rivers ../../data/oprvrs_gpkg_gb.zip \
  --manifest ./uk_world_data/manifest.json --out ./uk_world_data --width-metres 220

.venv/bin/ukgeo make-vegetation-tiles --landcover ../../data/FME_3564346A_1778997494261_5633.zip \
  --manifest ./uk_world_data/manifest.json --out ./uk_world_data --jobs 4
```

6. Validate and sample:

```bash
.venv/bin/ukgeo validate-tiles ./uk_world_data
.venv/bin/ukgeo stats ./uk_world_data
.venv/bin/ukgeo preview ./uk_world_data --layer height --out height_preview.png
.venv/bin/ukgeo preview ./uk_world_data --layer surface --out surface_geology.png
.venv/bin/ukgeo preview ./uk_world_data --layer vegetation --out vegetation.png
.venv/bin/ukgeo preview ./uk_world_data --layer rivers --out rivers_preview.png
.venv/bin/ukgeo preview ./uk_world_data --layer ore:zinc --out zinc_preview.png
.venv/bin/ukgeo preview ./uk_world_data --layer ore:coal --style overlay --max-size 12000 --out coal_on_height.png
.venv/bin/ukgeo preview ./uk_world_data --layer ores --max-size 12000 --out ores_all.png
.venv/bin/ukgeo preview ./uk_world_data --layer ore:granite --out granite_preview.png
.venv/bin/ukgeo preview ./uk_world_data --layer height --max-size 12000 --out height_large.png
.venv/bin/ukgeo hover-map ./uk_world_data --max-size 12000
.venv/bin/ukgeo sample ./uk_world_data --x 0 --z 0
```

`hover-map` opens an interactive heightmap window. Mouse wheel zooms around the cursor, middle/right drag pans, and moving the mouse over the map shows Minecraft `x/z`, height, tile/cell, and British National Grid easting/northing. Left click copies the Minecraft `x z` pair to the clipboard.

7. Copy `uk_world_data` to the Minecraft client/server root, or configure the mod to point at that directory.
8. Launch NeoForge 1.21.1 with the `ukgeo` mod and create/select the `ukgeo:uk_geological_create` world preset.
9. Optionally use Chunky to pre-generate selected areas only. Full pre-generation is not required.

## Build and test

```bash
cd tools/ukgeo-tools
.venv/bin/pytest

cd ../../mods/ukgeo
./gradlew build
```
