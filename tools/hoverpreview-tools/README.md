# UKGeo Hover Preview Tools

This directory contains helper scripts and PyInstaller packaging for the UKGeo hover
preview map. The Python package source remains in `../ukgeo-tools/src/ukgeo`.

Generate hover preview layers with:

```bash
./generate_hover_previews.sh
```

Open an existing hover preview map with:

```bash
./open_hover_map.sh
```

Build native binaries with:

```bash
./build_hover_binaries.sh
```

By default, artifacts are written to `dist-hover/` in this directory. Set `DIST_DIR`
to write them elsewhere.

The finished executable expects a `hoverpreviews` directory next to it at runtime.
