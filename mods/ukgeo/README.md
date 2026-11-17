# 271 UK Create

This is a NeoForge 1.21.1 / Java 21 mod with mod id `ukgeo`.

## Build

```bash
cd mods/ukgeo
./gradlew build
```

To deploy only UKGeo to the configured client and stopped server, with backups:

```bash
cd /home/zenyfh/Documents/GitHub/mapcreator
ONLY_UKGEO=1 ./scripts/deploy-uk-create.sh all
```

This builds UKGeo with the configured JDK 21, backs up previous `ukgeo-[0-9]*.jar` files under each instance's `backups/ukgeo-jar-<timestamp>`, and installs the new JAR. Other mods (including `ukgeo-animals`), configs, scripts, and world data are preserved. This mode does not start or restart Minecraft. The usual `client` and `server` targets also accept `ONLY_UKGEO=1`; use `DO_BUILD=0` when the JAR has already been built.

## Runtime data

Generate `uk_world_data` with `tools/ukgeo-tools`, then place it in the game or server root:

```text
<game root>/uk_world_data/manifest.json
<game root>/uk_world_data/height/000_000.r16.gz
<game root>/uk_world_data/ores/coal/000_000.u8.gz
```

If the directory is missing or invalid, the generator logs a warning and creates fallback flat terrain.

You can override the data directory in `config/ukgeo-common.toml`:

```toml
data_root = "/absolute/path/to/uk_world_data"
```

Relative paths are resolved from the game or server root.

## Worldgen stability knobs

UKGeo's local terrain, water, ore, and flora placement runs by default. Delegated vanilla/modded biome feature decoration is opt-in because some modded feature graphs can recursively wait for neighbouring chunks during teleport or edge-of-view generation.

To re-enable delegated biome features for testing:

```text
-Dukgeo.enableBiomeFeatureDecoration=true
```

To force-disable them even if another launch profile enables them:

```text
-Dukgeo.disableBiomeFeatureDecoration=true
```

## World preset

The bundled preset is `ukgeo:uk_geological_create` and the custom generator type is `ukgeo:heightmap`.
If the Minecraft UI does not expose custom presets in your pack, create the world from JSON/datapack settings that reference:

```json
{
  "type": "ukgeo:heightmap",
  "biome_source": {
    "type": "minecraft:fixed",
    "biome": "minecraft:plains"
  },
  "sea_level_y": 64,
  "min_y": -256,
  "gen_depth": 2288,
  "fallback_height": 72,
  "use_config_data_root": true
}
```

## Rivers / Strahler order

Channel half-widths follow OpenRiverNetwork Strahler order 1–7 ([openrivers.net](https://openrivers.net/)). At axis scale 1.0 the baked survival table is 1→1, 2→1, 3→2, 4→3, 5→5, 6→7, 7→10 (roughly ×2 at axis scale 2.0). The `uk_geological_create` preset sets `river_widen_radius` to `0` so runtime uses data-driven widths; `MIN_RIVER_CHANNEL_HALF_WIDTH` defaults to `1` so order-1 brooks can stay a single-block half-width.

Steep / mountain reaches are shallower (`slope ≥ 1` reduces max depth; `slope ≥ 2` caps at 2). Wet river columns keep their baked station water Y across local DEM depressions and bed-material changes. Dry banks retain their local DEM safety clamp. Bed smoothing preserves river water Y. Dry banks retain their world-coordinate distance/talus profile from the station resolver; finishing does not notch them once per nearby cascade/steep water cell or infer a new bank distance from the carved height. This avoids accumulated cuts, isolated raised blocks, and bank changes caused by chunk boundaries.

At DEM Y ≥ 128, the channel bed cannot cut more than three blocks below the original terrain, including after bed smoothing. The channel becomes shallower before changing its water level. If the permitted bed is at or above the baked water plane, that column keeps its original terrain and stays dry. A mismatched corridor through a mountainside therefore cannot create raised water sheets or a deep artificial canyon. This can narrow or interrupt an incompatible corridor; continuous flow there requires correcting the source river profile or alignment. Lowland valleys retain their existing incision behavior.

Highland smoothing preserves source DEM valleys inside active river corridors instead of filling them with the average height of surrounding hills. Protection covers the channel and its half-block edge jitter, then fades across the existing eight-block bank reach. Smoothing that lowers terrain still applies. This keeps compatible mountain streams from being buried above the incision limit, while their water remains on the baked station profile. It requires a mod update only and affects newly generated chunks; existing saved terrain is not repaired.

The [mountain bank fixture](src/test/resources/river-qa/mountain-banks.csv) records all 4,096 terrain columns at X −13376…−13313, Z −34320…−34257 near the reported jagged banks. It includes the current shaped DEM, reach/water tiles, half-block distance codes, half-widths, and four-block steepness samples. The test requests depth 2 and runs production river resolution and finishing across 16 complete chunks, asserting that dry bank profiles, water occupancy, and water Y survive finishing. A synthetic diagonal cascade also checks the three-block bank incision limit and compares overlapping banks under two chunk origins. These runtime changes affect new generation only and need no rebake.

### Lakes and structures

Each connected lake uses one terrain-derived water plane across chunk boundaries. Its level is the lower decile of DEM samples on the vegetation grid, which rejects isolated low pits and high shoreline samples. Chunk finishing no longer lifts separate lake fragments to local maxima. Bilinear freshwater coverage trims corners of the coarse vegetation cells without expanding water onto land; the source raster still limits shoreline detail. Lake depth follows the existing world-coordinate bathymetry.

Lake sampling also caches land and rejected river artifacts, bounded to 65,536 negative coarse cells using the same concurrent FIFO cache as terrain queries. Overlapping shore and river-mouth scans reuse those classifications. Shore coverage and cached water levels do not wait for another thread to resolve a new lake plane; only new component-plane calculations serialize. Concurrent cold misses may duplicate classification, while lake geometry and levels remain unchanged. `LakeSurfaceTest` covers overlapping scans, bounded eviction, concurrent queries, and progress during a blocked component calculation. This runtime fix needs only a mod update, with no data rebake.

Structure height queries include inland water and distinguish water surface from ocean-floor heightmaps. Structures use their normal water placement behavior: UKGeo does not reject a start because it overlaps a river or lake. The existing mountain-village and near-land monument restrictions remain in safe decoration mode. Terrain water repairs finish before decoration so village paths, underwater structures, dry interiors, and structure-placed water survive. This applies to new generation; it does not restore previously rejected structures or repair saved builds.

On chunk load in UKGeo worlds, an early listener removes only the empty `StructureStart.INVALID_START` placeholders left by the former water-overlap filter, before Surveyor indexes structures. Actual structure starts remain intact. This prevents the null-structure registry lookup that crashed Surveyor 1.2.4, including when reloading saved placeholders. No world reset or data rebake is needed. `LegacyStructureStartsTest` checks placeholder cleanup and retention of other starts.

Structure height queries run before a chunk height window exists. They use the cached dataset sampler, as do LOD queries without a window. `HeightTileWindowTest` covers this fallback with real temporary height tiles, window priority, missing data, and tile boundaries; no Minecraft registry bootstrap is needed.

### River regression checks and previews

From the repository root, with JDK 21 (the system default may be newer):

```bash
cd mods/ukgeo
JAVA_HOME="$HOME/.jdks/temurin-21.0.11" \
PATH="$HOME/.jdks/temurin-21.0.11/bin:$PATH" ./gradlew test build
cd ../..
tools/ukgeo-tools/.venv/bin/python -m pytest -q tools/ukgeo-tools/tests/test_river_stations.py
python3 tools/ukgeo-tools/scripts/plot_river_qa.py
```

The plotting command needs NumPy and Matplotlib. `RiverPlanningTest` and `LakeSurfaceTest` write CSV reports to `mods/ukgeo/build/reports/river-qa/`; the script plots them to `tools/ukgeo-tools/tests/output/river_qa_{spawn,mountain,lake}.png`.

The [spawn fixture](src/test/resources/river-qa/spawn.csv) samples the local dataset at X −80…−33, Z 160…223, including the reported location −57, 185. It records raw DEM converted with the station baker's Minecraft Y defaults, reach/station ownership, baked water Y, and river distance/width. The test requests depth 3 and runs the production Java finishing passes across 12 chunks. The former DEM clamp would lower 236 of its 2,396 wet columns; the corrected planner keeps all of them at the baked Y=71. Station owner IDs identify stamped cells; original station positions are not stored in these tiles.

The mountain preview is synthetic: a sloping DEM, requested depth 5, and a four-block baked cascade. It checks that finishing passes preserve water levels and the incision budget. The [lake fixture](src/test/resources/river-qa/lake-coarse.csv) contains 741 actual coarse freshwater cells around −396, 433, including shoreline samples at Y=71…73; the model gives the connected lake a shared Y=72. Its preview compares coarse mask/DEM inputs with corner trimming and the shared plane. Production also filters river artifacts from the freshwater mask.

The [mountain valley fixture](src/test/resources/river-qa/mountain-valley.csv) records 1,193 river-mask cells across 232 stations of reach 187314 near −13605, −34286. Source heights, the preset's radius-20 / step-10 neighbourhood means, and the installed station tiles reproduce the missing-stream report. At that location, smoothing lifts source terrain from Y=245 to Y=251 above baked water Y=245. Previously 167 stations had no wet recorded cells; valley protection retains wet cells at all 232 stations, including after finishing passes. Six individual cells still fail the original incision constraint. Unrecorded cells are filled with dry terrain for that finishing check; it is not a full Minecraft generation or fluid-connectivity test.

Preview comparisons show removed rules and planned geometry, not a rendered Minecraft world. These checks do not exercise final block placement, lake-mouth blending, modded structure integration, or later fluid ticks. Validate the new lake/structure changes in a new test world around −396, 433 and −177, 310. Floor material is selected after resolving channel height and cannot change the planned water level.

The runtime lake/structure fixes and the previously confirmed river DEM-clamp/geology fixes require only a rebuilt mod JAR. The raised slabs at −252, 385 and −85, 256 are different: their installed station data already contains Y=77 and Y=72 respectively, next to Y=71 water. The baker now constrains downstream reach profiles to incoming junction levels, producing Y=71 at both points without per-column DEM clamping. Those corrections require rebaking the four station layers; see [isolated rebuild instructions](../../tools/ukgeo-tools/README.md#river-station-junction-corrections). No height, geology, vegetation, distance, or hover layers need rebuilding. Regional previews are diagnostic only because corrections can continue beyond their tile boundaries.

The QA commands leave live worlds and runtime data untouched. The confirmed steep-geology fallback remains covered by the registry-independent geology tests.

## Commands

```text
/ukgeo check
/ukgeo cache
/ukgeo perf
/ukgeo sample <x> <z>
/ukgeo water <x> <z>
/ukgeo where <x> <z>
```


## Distant Horizons (LOD)

When [Distant Horizons](https://modrinth.com/mod/distanthorizons) (`distanthorizons`) is installed, UKGeo registers a custom DH world generator for levels using `ukgeo:heightmap`. LODs are built from UK height/surface tiles via `surfaceY` / `sampleLodColumn` — **not** via Minecraft `fillFromNoise` / DH `INTERNAL_SERVER` full chunk generation. Ores, caves, trees, and structures are skipped on the LOD path.

Enable (default when DH is present / flag unset):

```text
-Dukgeo.enableDhLodGenerator=true
```

Disable (dedicated server `config/server/user_jvm_args.txt` sets this so DH uses normal chunk loading):

```text
-Dukgeo.enableDhLodGenerator=false
```

Look for `UKGeo DH LOD generator registered` (or a skipped reason) in the log once per session.

## Debug / perf JVM flags

Useful dedicated-server `-D` flags (see `config/server/user_jvm_args.txt`):

```text
-Dukgeo.debugPerfSummary=true
-Dukgeo.perfLogIntervalChunks=100
-Dukgeo.debugWaterPlan=true
-Dukgeo.debugWaterX=-43
-Dukgeo.debugWaterZ=26
-Dukgeo.debugWaterY=75
-Dukgeo.debugWaterRadius=48
-Dukgeo.slowFillFromNoiseWarnMs=250
-Dukgeo.slowApplyBiomeDecorationWarnMs=250
```

Do **not** enable `-Dukgeo.debugTimingSpam` (too noisy). Optional: `-Dukgeo.debugGenTimings`, `-Dukgeo.debugCaves`, `-Dukgeo.debugOrePlacement`.

`/ukgeo check` and `/ukgeo where` print `axis_scale` from the TileManifest when present.

## Map scale (website vs server)

Runtime `uk_world_data` targets `axis_scale` **2.0** (~13 m/block, world ~68462×100000). Older 1.0 datasets (~26 m/block, ~33462×50000) need `serverXZ ≈ websiteXZ / 2` if mixed with a 2× hover map. Vertical `height_scale` in the world preset is terrain exaggeration only, not horizontal map scale.

## Create zinc

The mod does not compile against Create. Zinc ore block ids are resolved by registry id at runtime. If `create:zinc_ore` or `create:deepslate_zinc_ore` is absent, zinc placement is skipped and a warning is logged. In a final modpack, make Create required in your pack manifest or loader metadata if zinc generation is required.
