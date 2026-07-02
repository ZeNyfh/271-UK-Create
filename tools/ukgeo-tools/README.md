# ukgeo-tools

Python 3.11+ preprocessing tools for 271 UK Create.

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


### Runtime tile compression

New datasets default to uncompressed runtime tiles: `height/*.r16` and `*.u8` layers. This uses more disk space than the old `*.r16.gz`/`*.u8.gz` files, but it avoids thousands of small gzip decompressions while Minecraft is generating chunks. The mod and tools remain backward-compatible with existing `.gz` datasets.

To force the legacy gzip output, set:

```bash
UKGEO_TILE_COMPRESSION=gzip ./rebuild_uk_world_data_gb.sh
```

For faster in-game generation, leave it unset or set:

```bash
UKGEO_TILE_COMPRESSION=none ./rebuild_uk_world_data_gb.sh
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

The full GB rebuild script uses the repository defaults, where Minecraft `(0,0)` is aligned with the Nottingham area for the `0..650000` by `0..1300000` British National Grid extent.

For the national BGS 625k GeoPackage, use the 625k rules. These include the normal ore layers plus geology block layers such as granite, limestone, calcite, tuff, and optional Create stone types. For detailed whole-region ironstone belts, prefer the full BGS Geology 50k package with `examples/ore_rules.yml`: the 50k bedrock and linear layers contain named ironstone formations/members and iron-rich lithologies that the more general 625k overview may not retain.

The full GB rebuild also max-merges the checked-in `../../data/uk_iron_ore_reference_overlay.svg` red vector mask into the generated iron ore tiles. By default the overlay is registered from the SVG's blue UK outline to the generated dataset's valid land/height bounds. Set `IRON_OVERLAY_IMAGE=` to disable that overlay.

```bash
.venv/bin/ukgeo make-ore-tiles \
  --bgs ../../data/BGS_Geology_50k_GeoPackage.zip \
  --rules examples/ore_rules.yml \
  --manifest ./uk_world_data/manifest.json \
  --out ./uk_world_data \
  --jobs 4
```

For the 625k overview workflow:

```bash
.venv/bin/ukgeo make-ore-tiles \
  --bgs ../../data/BGS_Geology_625k_bedrock_gpkg.zip \
  --rules examples/ore_rules_625k.yml \
  --manifest ./uk_world_data/manifest.json \
  --out ./uk_world_data \
  --jobs 4

.venv/bin/ukgeo make-coal-resource-tiles \
  --coal-resources ../../data/OGC_CoalResourcesForNewTechnologies.zip \
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

Merge COP30 GeoTIFF elevation from `rasters_COP30.tar.gz` into an existing height dataset for Ireland, Northern Ireland, the Isle of Man, and nearby Irish/IoM islands:

```bash
.venv/bin/ukgeo add-cop30-height-tiles \
  --cop30 ../../data/rasters_COP30.tar.gz \
  --manifest ./uk_world_data/manifest.json \
  --out ./uk_world_data \
  --resampling bilinear \
  --smoothing light \
  --height-deterrace \
  --target ireland-iom \
  --protect-mainland-gb
```

The COP30 overlay reads GeoTIFFs directly from the `.tar.gz`, reprojects them to the manifest CRS, writes UKGeo `height/*.r16` tiles in decimetres, and records a `height_overlays` entry in `manifest.json`. `--target` can be `ireland-iom`, `ireland-only`, `iom-only`, or `all-cop30`; `--protect-mainland-gb` remains enabled by default so overlapping COP30 pixels cannot replace England, Wales, Scotland, or Anglesey. `--minecraft-y-offset` is available for manual tuning but defaults to `0`. Use `--debug-written-geotiff ./debug_cop30_written.tif`, `--debug-target-mask-geotiff ./debug_cop30_target.tif`, or `--debug-land-mask-geotiff ./debug_cop30_land.tif` to inspect sampled/written cells and masks.

The old Northern Ireland OSNI 50m DTM overlay remains available for manual legacy use:

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

Generate vegetation class tiles from the LCM 2024 GeoTIFF zip:

```bash
.venv/bin/ukgeo make-vegetation-tiles \
  --landcover ../../data/FME_3564346A_1778997494261_5633.zip \
  --manifest ./uk_world_data/manifest.json \
  --out ./uk_world_data \
  --jobs 4
```

This command writes two vegetation-derived layers by default:

- `vegetation/`: raw landcover classes for fine surface, flora, and hover detail.
- `biome_regions/`: a coarser dominant-landcover layer used by the Minecraft `BiomeSource` so biomes form broad coherent regions instead of raw raster speckles.

Tune the region layer with `--biome-region-factor`, `--biome-region-smoothing-passes`, and `--biome-region-min-area-cells`, or disable it with `--no-generate-biome-regions` for legacy manifests.

To remake the full checked-out GB dataset into `./uk_world_data_gb`, run:

```bash
./rebuild_uk_world_data_gb.sh
```

By default the rebuild script uses `../../data/rasters_COP30.tar.gz` for Ireland, Northern Ireland, and the Isle of Man, applies no vertical offset, and expands the west edge of the British National Grid extent so western Ireland is not silently clipped. Set `COP30_MINECRAFT_Y_OFFSET` only for manual tuning. Set `INCLUDE_IRELAND=0` to keep the older GB-only extent. Set `USE_LEGACY_OSNI_HEIGHT=1` to use `../../data/osni_opendata_50m_dtm.zip` instead of COP30 for the legacy Northern Ireland-only overlay.

The rebuild script writes to a temporary sibling directory first, validates the result, then moves the previous dataset to a timestamped backup before replacing it.
Ore and vegetation generation use 4 worker processes by default; lower them on memory-constrained machines with `UKGEO_TILE_COMPRESSION=none ORE_JOBS=2 VEGETATION_JOBS=2 ./rebuild_uk_world_data_gb.sh`.

## Generate the default 25k x 50k world

Use the same commands without overriding `--world-width` or `--world-depth`.
The first height implementation uses a guarded in-memory output mosaic; for low-memory machines, generate smaller extents or replace the height path with a windowed VRT workflow.

## Validate, preview, sample

```bash
.venv/bin/ukgeo validate-tiles ./uk_world_data
.venv/bin/ukgeo stats ./uk_world_data
.venv/bin/ukgeo preview ./uk_world_data --layer height --out preview.png
.venv/bin/ukgeo preview ./uk_world_data --layer surface --out surface_geology.png
.venv/bin/ukgeo preview ./uk_world_data --layer vegetation --out vegetation.png
.venv/bin/ukgeo preview ./uk_world_data --layer biome_regions --out biome_regions.png
.venv/bin/ukgeo preview ./uk_world_data --layer rivers --out rivers.png
.venv/bin/ukgeo preview ./uk_world_data --layer ore:zinc --out zinc.png
.venv/bin/ukgeo preview ./uk_world_data --layer ore:coal --style overlay --max-size 12000 --out coal_on_height.png
.venv/bin/ukgeo preview ./uk_world_data --layer ores --max-size 12000 --out ores_all.png
.venv/bin/ukgeo preview ./uk_world_data --layer ore:granite --out granite.png
.venv/bin/ukgeo preview ./uk_world_data --layer height --style gray --out height_gray.png
.venv/bin/ukgeo preview ./uk_world_data --layer height --max-size 12000 --out height_large.png
../hoverpreview-tools/generate_hover_previews.sh ./uk_world_data ./hoverpreviews
PREVIEWS=./hoverpreviews ../hoverpreview-tools/open_hover_map.sh ./uk_world_data
.venv/bin/ukgeo sample ./uk_world_data --x 0 --z 0
```

The preview PNGs show the whole generated map extent, downscaled so the longest side is `--max-size` pixels. Use a larger `--max-size` for a more detailed whole-map image. `--max-size 0` writes native tile resolution, but the default 25k x 50k world is a very large image and can require several GB of RAM.

`../hoverpreview-tools/generate_hover_previews.sh` writes a `hoverpreviews` folder containing stackable PNG layers (`layers/height.png`, `layers/surface.png`, `layers/vegetation.png`, optional `layers/biome_regions.png`, `layers/rivers.png`, and one PNG per ore under `layers/ores/`) plus downsampled `mips/` versions and sample PNGs for hover text. `../hoverpreview-tools/open_hover_map.sh` reads those pre-rendered images instead of processing raw tile layers in the GUI, starts at a fit-to-window zoom, and dynamically picks lower-resolution mips when zoomed out. Mouse wheel zooms around the cursor, middle/right drag pans, and hovering shows Minecraft `x/z`, height, tile/cell, vegetation/biome-region data where available, and British National Grid easting/northing. Left click copies the Minecraft `x z` pair to the clipboard.

To build the standalone hover app:

```bash
../hoverpreview-tools/build_hover_binaries.sh
```

The generated executable/binary expects a `hoverpreviews` folder in the same directory when launched. It bundles Python and the Python packages it needs, so the target machine does not need Python, NumPy, Pillow, or PyInstaller installed. On Linux the script builds `../hoverpreview-tools/dist-hover/ukgeo-hover-linux`; it also builds `../hoverpreview-tools/dist-hover/ukgeo-hover.exe` when run on Windows, or from Linux if Docker is available for the Windows PyInstaller image.

Copy the finished `uk_world_data` directory to the Minecraft client/server root, or point the mod config at another directory.
