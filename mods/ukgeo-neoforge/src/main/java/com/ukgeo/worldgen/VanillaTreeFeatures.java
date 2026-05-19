package com.ukgeo.worldgen;

import java.util.Locale;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.features.TreeFeatures;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;

public final class VanillaTreeFeatures {
    private VanillaTreeFeatures() {
    }

    public static Optional<TreeType> type(String name) {
        String normalized = name.toLowerCase(Locale.ROOT).replace('-', '_');
        for (TreeType type : TreeType.values()) {
            if (type.id.equals(normalized)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }

    public static boolean placeTree(ServerLevel level, BlockPos pos, String treeType) {
        return type(treeType)
            .map(type -> placeTree(level, pos, type))
            .orElse(false);
    }

    public static boolean placeTree(ServerLevel level, BlockPos pos, TreeType treeType) {
        return placeTree(level, pos, treeType.key());
    }

    public static boolean placeTree(ServerLevel level, BlockPos pos, ResourceKey<ConfiguredFeature<?, ?>> treeKey) {
        if (level.isClientSide()) {
            return false;
        }
        ConfiguredFeature<?, ?> feature = level.registryAccess()
            .registryOrThrow(Registries.CONFIGURED_FEATURE)
            .getOrThrow(treeKey);

        return feature.place(
            level,
            level.getChunkSource().getGenerator(),
            level.getRandom(),
            pos
        );
    }

    public enum TreeType {
        OAK("oak", TreeFeatures.OAK),
        BIRCH("birch", TreeFeatures.BIRCH),
        SPRUCE("spruce", TreeFeatures.SPRUCE),
        FANCY_OAK("fancy_oak", TreeFeatures.FANCY_OAK),
        JUNGLE("jungle", TreeFeatures.JUNGLE_TREE),
        ACACIA("acacia", TreeFeatures.ACACIA),
        DARK_OAK("dark_oak", TreeFeatures.DARK_OAK),
        PINE("pine", TreeFeatures.PINE),
        MEGA_SPRUCE("mega_spruce", TreeFeatures.MEGA_SPRUCE),
        MEGA_PINE("mega_pine", TreeFeatures.MEGA_PINE),
        SWAMP_OAK("swamp_oak", TreeFeatures.SWAMP_OAK);

        private final String id;
        private final ResourceKey<ConfiguredFeature<?, ?>> key;

        TreeType(String id, ResourceKey<ConfiguredFeature<?, ?>> key) {
            this.id = id;
            this.key = key;
        }

        public String id() {
            return id;
        }

        public ResourceKey<ConfiguredFeature<?, ?>> key() {
            return key;
        }
    }
}
