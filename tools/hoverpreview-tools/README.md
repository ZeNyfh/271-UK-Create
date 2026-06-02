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

By default, artifacts are written to `hoverpreviews/` in the current dataset
directory. The export contains:

- `hover_manifest.json` metadata.
- `layers/` PNG map layers.
- `mips/` downsampled layer images for fast zoomed-out rendering.
- `samples/` images used by the website to report height and layer values under
  the pointer.

## Optional GPU rendering

The exporter can use an NVIDIA GPU for the large per-pixel preview rendering
steps when CuPy is installed:

```bash
python -m pip install -e "./tools/hoverpreview-tools[gpu]"
HOVERPREVIEW_GPU=1 ./tools/hoverpreview-tools/generate_hover_previews.sh
```

`HOVERPREVIEW_GPU=auto` is the default. It uses CuPy when available and falls
back to CPU rendering otherwise. Tile reads and PNG encoding still run on the
CPU, so the speedup depends on where the local run spends its time.

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

The included `.github/workflows/pages.yml` workflow stages these root files for
GitHub Pages. Use the workflow when Pages is configured for **GitHub Actions**;
if Pages is configured for **Deploy from a branch**, choose the `main` branch and
repository root so GitHub serves the checked-in root `index.html`.

## If GitHub Pages shows the README

GitHub Pages falls back to rendering `README.md` when the published artifact does
not contain a root `index.html`. This repo includes `.github/workflows/pages.yml`
to publish a staged root site containing `index.html`, the viewer assets, and the
`tools/ukgeo-tools/uk_world_data_gb/hoverpreviews/` data folder when it exists.

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
