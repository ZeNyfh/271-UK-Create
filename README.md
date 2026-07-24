# 271 UK Create

Repository for the 271 UK Create world data pipeline, hover preview site, and supporting NeoForge/KubeJS subprojects.

Quick links:

- [Published hover map](https://zenyfh.github.io/271-UK-Create/)
- [UKGeo tool README](tools/ukgeo-tools/README.md)
- [Data notes](data/README.md)

## What is in this repository

This is not a single mod. It is a workspace with data tools, a static map site, and several gameplay/runtime subprojects.

### Tools

| Path                       | Purpose                                                                                                       |
|----------------------------|---------------------------------------------------------------------------------------------------------------|
| `tools/ukgeo-tools`        | Python preprocessing pipeline for height, ores, rivers, vegetation, animal habitats, and manifest generation. |
| `tools/hoverpreview-tools` | Hoverpreview exporter and static site assets for the published map.                                           |

### Mods and runtime content

| Path                       | Purpose                                                                           |
|----------------------------|-----------------------------------------------------------------------------------|
| `mods/ukgeo`               | Main worldgen/runtime mod for the 271 UK Create map.                              |
| `mods/realtime-localised-weather` | Required client/server UKGeo weather replacement driven by Open-Meteo regional data. |
| `mods/ukgeo-animals`       | Animal habitat integration for UKGeo/WilderNature spawning.                       |
| `mods/animalhunger`        | Persistent animal hunger, trough feeding, grazing, and optional Jade integration. |
| `mods/foodspoilage`        | Dynamic food spoilage.                                                            |
| `mods/pollution`           | Chunk-level machine pollution with visual effects.                                |
| `mods/vanillaadjust`       | Miscellaneous vanilla balance and behaviour adjustments.                          |
| `mods/createenginebalance` | Create Diesel Generators engine fuel-balance changes.                             |
| `mods/kubejs`              | KubeJS scripts for recipes and server-side customisations.                        |

## Required local setup

Before running the rebuild/build scripts, assume these requirements:

- Python 3.11+
- JDK 21
- A writable `data/` directory with the external source archives referenced by the config files

Use JDK 21 specifically. Several module build scripts already try to prefer:

- `$HOME/.jdks/temurin-21.0.11`

If your local Java is newer, Gradle/userdev tasks may fail. Set `JAVA_HOME` explicitly if needed.

## Full development client

Run `./start-dev.sh` from the repository root. It builds every folder under `mods/`, stages only
those workspace-built modules into the Realtime Localised Weather Gradle run directory, and
launches its NeoForge development client. It does not load jars from your configured modpack.
The weather and UKGeo duplicate jars are excluded because Gradle loads those directly from the
workspace. It adds only the external runtime integrations required or supported by workspace
mods: Create, Create Diesel Generators, Create Aeronautics, Create Simulated, WilderNature,
Jade, Serene Seasons, Sable, Architectury, Curios, and GlitchCore.

## Configuration model

The current defaults live in:

- [tools/ukgeo-tools/config.yml](tools/ukgeo-tools/config.yml)
- [tools/hoverpreview-tools/config.yml](tools/hoverpreview-tools/config.yml)

Important sections:

- `tools/ukgeo-tools/config.yml`
  - `runtime`: runtime tile format defaults
  - `rebuild`: full dataset rebuild inputs and world sizing
  - `previews`: PNG preview generation defaults
- `tools/hoverpreview-tools/config.yml`
  - `generate`: hoverpreview export defaults
  - `local_server`: host/port for local preview serving

If you need to change dataset inputs, Ireland inclusion, scale, preview size, worker counts, or local server settings, change the YAML files first rather than exporting ad-hoc shell variables.

## Core workflows

### 1. Install the Python tool environments

```bash
cd tools/ukgeo-tools
python3 -m venv .venv
.venv/bin/python -m pip install -e ".[test]"

cd ../hoverpreview-tools
python3 -m venv .venv
.venv/bin/python -m pip install -e ".[test]"
```

### 2. Rebuild the main UK dataset

Default rebuild script:

```bash
cd tools/ukgeo-tools
./rebuild_uk_world_data_gb.sh
```

This script reads `config.yml` section `rebuild` and rebuilds the default dataset into:

- `tools/ukgeo-tools/uk_world_data_gb`

It currently covers height, BGS ores, coal, gold, surface geology, rivers, vegetation, animal habitats, and the Republic of Ireland ore SVG overlay.

### 3. Generate normal PNG previews

```bash
cd tools/ukgeo-tools
./generate_previews.sh
```

This reads `config.yml` section `previews`.

### 4. Generate hoverpreview site data

Default hoverpreview export:

```bash
cd tools/hoverpreview-tools
./generate_hover_previews.sh --regenerate preview
```

Useful variants:

```bash
./generate_hover_previews.sh --regenerate all
./generate_hover_previews.sh --regenerate ores,animals,preview
./generate_hover_previews.sh --workers 1 --tile-size 512
```

This reads `tools/hoverpreview-tools/config.yml` section `generate`.

By default it writes hover data into:

- `tools/ukgeo-tools/uk_world_data_gb/hoverpreviews`

That directory is what the published site expects to load.

### 5. Serve the hover map locally

From the repository root:

```bash
./hoverpreview-local.sh tools/ukgeo-tools/uk_world_data_gb/hoverpreviews
```

This uses `tools/hoverpreview-tools/config.yml` section `local_server` and serves the repository root over HTTP so the site can load preview assets with the same relative paths it uses on GitHub Pages.

## Published site

The published map at:

- `https://zenyfh.github.io/271-UK-Create/`

loads committed hoverpreview assets from the repository, not from a server-side rebuild step.

In practice that means:

1. rebuild dataset locally
2. regenerate hoverpreviews locally
3. commit the generated preview/data files that the site needs

If the site is missing layers, the problem is usually missing or stale committed assets under:

- `tools/ukgeo-tools/uk_world_data_gb/hoverpreviews`

## Building mods

Most compiled NeoForge subprojects include a local `build.sh`.

Typical usage:

```bash
cd mods/ukgeo
./build.sh
```

or:

```bash
cd mods/animalhunger
./build.sh
```

The per-mod build scripts generally do two things:

1. run the Gradle build
2. copy the produced jar into a local Minecraft instance `mods/` folder

These scripts are machine-specific because they copy into the configured local Minecraft instance:

- [config/minecraft-instance.yml](config/minecraft-instance.yml)

The old `/media/zenyfh/GoodHDD/...` instance path is kept commented in that YAML file. If your local instance path differs, update `instance_dir` and `mod_dir` there before using the build scripts.

The root-level [build.sh](build.sh) is narrower: it builds `mods/ukgeo` and copies only that jar.

## Notes by subproject

- `tools/ukgeo-tools`
  - main data rebuild pipeline
  - uses config-driven defaults
  - owns the checked-out runtime dataset
- `tools/hoverpreview-tools`
  - exports site layers, mips, tiles, and hover sample data
  - local preview serving is done from the repo root via `hoverpreview-local.sh`
- `mods/kubejs`
  - not a Gradle-built Java mod
  - contains KubeJS scripts such as server recipe overrides
- `mods/ukgeo-animals`
  - depends on `ukgeo`
  - integrates habitat data with spawning
- `mods/realtime-localised-weather`
  - depends on `ukgeo`
  - adds required regional live/manual/vanilla weather authority for UKGeo worlds
- `mods/createenginebalance`
  - depends on Create + Create Diesel Generators

## Recommended order for a full local refresh

```bash
cd tools/ukgeo-tools
./rebuild_uk_world_data_gb.sh

cd ../hoverpreview-tools
./generate_hover_previews.sh --regenerate all --workers 1

cd ../..
./hoverpreview-local.sh tools/ukgeo-tools/uk_world_data_gb/hoverpreviews
```

Then, if you changed runtime mods, build the relevant mod subprojects with their local `build.sh` scripts.

## Testing

For the Python tooling:

```bash
cd tools/ukgeo-tools
.venv/bin/pytest

cd ../hoverpreview-tools
.venv/bin/pytest
```

For Java/NeoForge modules, use Gradle from inside the module directory:

```bash
cd mods/vanillaadjust
JAVA_HOME="$HOME/.jdks/temurin-21.0.11" PATH="$HOME/.jdks/temurin-21.0.11/bin:$PATH" ./gradlew compileJava
```

Use the same JDK 21 pattern for the other Java mod subprojects if your shell defaults to a newer JVM.
