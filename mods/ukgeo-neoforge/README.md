# UK Geological Create Worldgen NeoForge Mod

This is a NeoForge 1.21.1 / Java 21 mod with mod id `ukgeo`.

## Build

```bash
cd mods/ukgeo-neoforge
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

## Commands

```text
/ukgeo check
/ukgeo sample <x> <z>
/ukgeo cache
```

## Create zinc

The mod does not compile against Create. Zinc ore block ids are resolved by registry id at runtime. If `create:zinc_ore` or `create:deepslate_zinc_ore` is absent, zinc placement is skipped and a warning is logged. In a final modpack, make Create required in your pack manifest or loader metadata if zinc generation is required.
