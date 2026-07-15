# UKGeo Hover Preview Website

This directory contains the hover-preview export tooling plus the static files used
by the GitHub Pages hover map. The browser viewer is intentionally simple: it
loads generated `hoverpreviews/` data through the repository-root page, matching
the same layer controls, scroll-bar layout, status overlay, and mouse behaviour
as the published site.

## Generate preview assets

Generate stackable hover preview layers with:

```bash
./generate_hover_previews.sh
```

User-configurable export defaults now live in
[config.yml](/home/zenyfh/Documents/GitHub/mapcreator/tools/hoverpreview-tools/config.yml).

By default, artifacts are written to `hoverpreviews/` in the current dataset
directory. The export contains:

- `hover_manifest.json` metadata.
- `layers/` full-resolution visual layer images.
- `mips/` downsampled visual layer images for fast zoomed-out rendering.
- `tiles/` a slippy-map-style tile pyramid for every visual MIP level.
- `samples/` exact hover-data sample images.
- `sample_tiles/` exact lossless PNG sample tiles used for hover reads.

For GitHub Pages deployment, only `hover_manifest.json`, `tiles/`, and
`sample_tiles/` are required by the browser. `layers/`, `mips/`, and `samples/`
are redundant for the published site.

The browser chooses a MIP level for the current zoom, loads only visible tiles,
and keeps a bounded least-recently-used bitmap cache. Hover status reads come
from `sample_tiles/`, not from visual RGB tiles, so categorical classes and
height values stay exact even when visual MIPs are smoothed.

Sample tiles are always PNG/lossless. Visual tiles default to PNG and may be
written as WebP with `--visual-format webp`; do not use WebP for sample/data
tiles because ore, vegetation, river, and height values must be exact.

Useful export options:

```bash
./generate_hover_previews.sh --regenerate height,preview
./generate_hover_previews.sh --regenerate all
./generate_hover_previews.sh --tile-size 256 --workers 8 --visual-format png
./generate_hover_previews.sh --force --clean-stale --profile
# Set HOVERPREVIEW_DEPLOY_MINIMAL: true in config.yml to trim redundant files.
./generate_hover_previews.sh --regenerate preview
```

`--regenerate height,preview` rebuilds the height tiles and then exports the
hover map. The default height rebuild uses `data/rasters_COP30.tar.gz` for
Ireland, Northern Ireland, the Isle of Man, and nearby target islands, while
mainland Great Britain remains protected and based on OS Terrain 50. The old
OSNI Northern Ireland DTM is available only when explicitly requested. COP30
heights are written with no vertical offset by default; change
`COP30_MINECRAFT_Y_OFFSET` in `config.yml` only for manual tuning.

Examples:

```bash
# Default full preview with COP30 overlay
./generate_hover_previews.sh --regenerate height,preview

# Full rebuild
./generate_hover_previews.sh --regenerate all

# Legacy OSNI fallback
# Set USE_LEGACY_OSNI_HEIGHT: true in config.yml first
./generate_hover_previews.sh --regenerate height,preview

# Disable Ireland/IOM overlay entirely
# Set INCLUDE_IRELAND: false in config.yml first
./generate_hover_previews.sh --regenerate height,preview

# Produce debug COP30 overlay GeoTIFF
# Set COP30_DEBUG_GEOTIFF in config.yml first
./generate_hover_previews.sh --regenerate height
```

CLI options exposed by `python -m hoverpreview_tools.cli`:

- `--tile-size`: visual and sample tile size in pixels.
- `--workers`: bounded tile encoder worker count; `0` means auto.
- `--visual-format png|webp`: visual output format; sample tiles remain PNG.
- `--renderer auto|webgl|2d`: preferred browser renderer written into the
  manifest. `auto` tries WebGL and falls back to the existing 2D canvas path.
- `--force`: rewrite existing generated files.
- `--clean-stale`: remove tile files no longer referenced by the manifest.
- `--deploy-minimal`: delete redundant `layers/`, `mips/`, and `samples/`
  after export. Keep only the manifest plus visual/sample tiles.
- `--profile`: print rough per-layer generation timings.
- Existing options `--out`, `--max-size`, `--style`, and `--clean` remain
  supported.

The generated manifest explicitly describes every layer’s visual MIPs, visual
tile templates, sample tile templates, and sample encodings. Newer browser code
uses that metadata instead of hardcoding paths; older exports still fall back to
full/cropped sample images where tile metadata is missing.

## Optional GPU rendering

The exporter can use an NVIDIA GPU for the large per-pixel preview rendering
steps when CuPy is installed:

```bash
python -m pip install -e "./tools/hoverpreview-tools[gpu]"
```

Set `runtime.HOVERPREVIEW_GPU: true` in `config.yml` to require GPU rendering.
The default is `auto`, which uses CuPy when available and falls back to CPU
rendering otherwise. Tile reads and PNG encoding still run on the CPU, so the
speedup depends on where the local run spends its time.

For browser rendering, set `generate.HOVERPREVIEW_RENDERER` in
[config.yml](/home/zenyfh/Documents/GitHub/mapcreator/tools/hoverpreview-tools/config.yml).
`auto` is the default and prefers WebGL while falling back to the previous 2D
canvas renderer if WebGL is unavailable or fails at runtime.

## Publish from the repository root

This repository is configured for GitHub Pages served from the `main` branch at
the repository root, for example:

```text
https://zenyfh.github.io/271-UK-Create/
```

The root `index.html` loads the website assets from `tools/hoverpreview-tools/site/`
and loads preview data from this relative directory:

```text
tools/ukgeo-tools/uk_world_data_gb/hoverpreviews/hover_manifest.json
```

Before publishing, generate or copy the preview export to:

```text
tools/ukgeo-tools/uk_world_data_gb/hoverpreviews/
```

The included `.github/workflows/pages.yml` workflow does not regenerate preview
data. It stages the checked-in site files plus the checked-in
`hover_manifest.json`, `tiles/`, and `sample_tiles/` data for GitHub Pages.
Use the workflow when Pages is configured for **GitHub Actions**; if Pages is
configured for **Deploy from a branch**, choose the `main` branch and
repository root so GitHub serves the checked-in root `index.html`.

## If GitHub Pages shows the README

GitHub Pages falls back to rendering `README.md` when the published artifact does
not contain a root `index.html`. This repo includes `.github/workflows/pages.yml`
to publish a staged root site containing `index.html`, the viewer assets, and
the required subset of `tools/ukgeo-tools/uk_world_data_gb/hoverpreviews/`
when it exists.

In the repository settings, set **Pages → Build and deployment → Source** to
**GitHub Actions**, then run the **Deploy GitHub Pages site** workflow or push to
`main`. If the page loads but shows the empty-state text, commit the generated
`tools/ukgeo-tools/uk_world_data_gb/hoverpreviews/` directory; the ignore rules
allow that folder while keeping the rest of `uk_world_data_gb` ignored.

## Run locally

From the repository root, run:

```bash
./hoverpreview-local
```

To open a generated preview folder explicitly, pass either the folder or manifest:

```bash
./hoverpreview-local tools/ukgeo-tools/uk_world_data_gb/hoverpreviews
```

The script serves the whole checkout from the repository root and opens
<http://127.0.0.1:8000/> in the default browser. The page expects generated data
at `tools/ukgeo-tools/uk_world_data_gb/hoverpreviews/`. Do not open
`tools/hoverpreview-tools/site/index.html` directly; browser file URLs and
serving only `site/` do not match the published path layout.

## Viewer controls

- Move the pointer over the map to show Minecraft `x/z`, height, tile/cell,
  and British National Grid coordinates.
- Use the mouse wheel or toolbar buttons to zoom.
- Middle/right drag the map to pan.
- Left drag measures a distance; left click copies the Minecraft `x z` pair to
  the clipboard.
- Use the layer and ore controls to toggle overlays.
- Use **Show animals** beside the ore control to include or hide habitat animal
  lists in the hover status. Animal lookup uses the vegetation/landcover sample
  value first and falls back to aliases from `animals.txt`.

## Validation

From the repository root:

```bash
cd tools/hoverpreview-tools
python -m pytest
node --check site/app.js
```

To validate a COP30 preview generation path locally:

```bash
./generate_hover_previews.sh --regenerate height,preview --profile
```

For a full rebuild:

```bash
./generate_hover_previews.sh --regenerate all --profile
```

Inspect `manifest.json`, `hoverpreviews/hover_manifest.json`, and the generated
tile pyramids under `hoverpreviews/tiles/` and `hoverpreviews/sample_tiles/`.
If you did not use `--deploy-minimal`, you can also inspect
`hoverpreviews/layers/height*` and `hoverpreviews/samples/height_rgb.png`.
Ireland, Northern Ireland, and the Isle of Man should have valid height
samples; England, Wales, and Scotland should remain valid OS Terrain 50-derived
data.

Full preview generation can be expensive on the complete UKGeo dataset; use
`./generate_hover_previews.sh --profile` when regenerating production assets.
