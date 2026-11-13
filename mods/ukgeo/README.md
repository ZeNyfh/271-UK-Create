# 271 UK Create

This is a NeoForge 1.21.1 / Java 21 mod with mod id `ukgeo`.

## Build

```bash
cd mods/ukgeo
./gradlew build
```

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
