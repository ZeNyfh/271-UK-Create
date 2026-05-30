# UKGeo Hover Preview Website

This directory contains the hover-preview export tooling plus the static files used
by the GitHub Pages hover map. The browser viewer is intentionally simple: it
loads a generated `hoverpreviews/` directory from a relative path, matching the
same layer controls, scroll-bar layout, status overlay, and mouse behaviour as
the Tkinter hover application.

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

No GitHub Pages deployment workflow is required for this branch/root setup; Pages
will serve the checked-in root `index.html` and the relative preview data path.

## Run locally

From the repository root, serve the whole checkout so the relative paths match
GitHub Pages:

```bash
python3 -m http.server 8000
```

Then visit <http://localhost:8000>. The page expects the generated data at
`tools/ukgeo-tools/uk_world_data_gb/hoverpreviews/`.

For development from inside this directory, the copy under `site/` can also be
served directly:

```bash
python3 -m http.server 8000 --directory site
```

That version uses `../../ukgeo-tools/uk_world_data_gb/hoverpreviews/`
relative to `site/`, so it works when served from this repository layout.

## Viewer controls

- Move the pointer over the map to show Minecraft `x/z`, height, tile/cell,
  British National Grid coordinates, and enabled layer values.
- Use the mouse wheel or toolbar buttons to zoom.
- Middle/right drag the map to pan, matching the Python app.
- Left drag measures a distance; left click copies the Minecraft `x z` pair to
  the clipboard.

## Legacy desktop tooling

The Tkinter launcher remains available for native desktop use:

```bash
./open_hover_map.sh
```

Build native binaries with:

```bash
./build_hover_binaries.sh
```

By default, binary artifacts are written to `dist-hover/` in this directory. Set
`DIST_DIR` to write them elsewhere. The finished executable expects a
`hoverpreviews` directory next to it at runtime.
