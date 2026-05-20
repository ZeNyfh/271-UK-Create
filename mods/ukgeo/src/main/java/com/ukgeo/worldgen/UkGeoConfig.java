package com.ukgeo.worldgen;

import java.nio.file.Path;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class UkGeoConfig {
    public static final ModConfigSpec SPEC;
    private static final ModConfigSpec.ConfigValue<String> DATA_ROOT;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        DATA_ROOT = builder
            .comment("External uk_world_data directory. Leave blank to use <game/server root>/uk_world_data. Relative paths are resolved from the game/server root.")
            .define("data_root", "");
        SPEC = builder.build();
    }

    private UkGeoConfig() {
    }

    public static Path dataRoot(Path gameRoot) {
        String configured = DATA_ROOT.get().trim();
        if (configured.isEmpty()) {
            return gameRoot.resolve("uk_world_data");
        }
        Path path = Path.of(configured);
        return path.isAbsolute() ? path : gameRoot.resolve(path).normalize();
    }
}
