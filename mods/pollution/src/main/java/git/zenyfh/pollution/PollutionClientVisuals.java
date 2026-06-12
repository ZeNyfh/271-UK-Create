package git.zenyfh.pollution;

import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.FoliageColor;
import net.minecraft.world.level.GrassColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

public final class PollutionClientVisuals {
    private static final int LOW_GRASS_COLOR = 0x6F8A4A;
    private static final int MEDIUM_GRASS_COLOR = 0x7A7A3E;
    private static final int HIGH_GRASS_COLOR = 0x756638;
    private static final int SEVERE_GRASS_COLOR = 0x5E5534;
    private static final float FOG_TINT_RED = 0.58F;
    private static final float FOG_TINT_GREEN = 0.54F;
    private static final float FOG_TINT_BLUE = 0.45F;

    private PollutionClientVisuals() {
    }

    @EventBusSubscriber(modid = Pollution.MODID, value = Dist.CLIENT)
    public static final class ForgeBus {
        private ForgeBus() {
        }

        @SubscribeEvent
        public static void onClientPlayerTick(PlayerTickEvent.Post event) {
            if (event.getEntity().level().isClientSide()) {
                scanClientVisualSources(event.getEntity().level(), event.getEntity().blockPosition());
                PollutionClientState.clientTick(event.getEntity().blockPosition());
                processQueuedRerenders();
            }
        }

        @SubscribeEvent
        public static void onRenderFog(ViewportEvent.RenderFog event) {
            if (!PollutionConfig.POLLUTION_ENABLED.get() || !PollutionConfig.POLLUTION_FOG_ENABLED.get()) {
                return;
            }
            double strength = fogStrength();
            if (strength <= 0.001) {
                return;
            }

            float minMultiplier = PollutionConfig.POLLUTION_FOG_MIN_DISTANCE_MULTIPLIER.get().floatValue();
            float multiplier = (float) lerp(1.0, minMultiplier, strength);
            event.scaleFarPlaneDistance(multiplier);
            event.scaleNearPlaneDistance((float) lerp(1.0, Math.max(0.15F, minMultiplier), strength));
            event.setCanceled(true);
        }

        @SubscribeEvent
        public static void onFogColor(ViewportEvent.ComputeFogColor event) {
            if (!PollutionConfig.POLLUTION_ENABLED.get()
                    || !PollutionConfig.POLLUTION_FOG_ENABLED.get()
                    || !PollutionConfig.POLLUTION_FOG_COLOR_TINT_ENABLED.get()) {
                return;
            }
            double strength = fogStrength() * PollutionConfig.POLLUTION_FOG_COLOR_TINT_STRENGTH.get();
            if (strength <= 0.001) {
                return;
            }

            event.setRed((float) lerp(event.getRed(), FOG_TINT_RED, strength));
            event.setGreen((float) lerp(event.getGreen(), FOG_TINT_GREEN, strength));
            event.setBlue((float) lerp(event.getBlue(), FOG_TINT_BLUE, strength));
        }
    }

    @EventBusSubscriber(modid = Pollution.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ModBus {
        private ModBus() {
        }

        @SubscribeEvent
        public static void registerBlockColors(RegisterColorHandlersEvent.Block event) {
            event.register((state, level, pos, tintIndex) -> tintGrassColor(
                            level,
                            pos,
                            level != null && pos != null
                                    ? BiomeColors.getAverageGrassColor(level, state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos)
                                    : GrassColor.getDefaultColor()
                    ),
                    Blocks.LARGE_FERN,
                    Blocks.TALL_GRASS
            );

            event.register((state, level, pos, tintIndex) -> tintGrassColor(
                            level,
                            pos,
                            level != null && pos != null ? BiomeColors.getAverageGrassColor(level, pos) : GrassColor.getDefaultColor()
                    ),
                    Blocks.GRASS_BLOCK,
                    Blocks.SUGAR_CANE
            );

            event.register((state, level, pos, tintIndex) -> {
                        int base = level != null && pos != null ? BiomeColors.getAverageGrassColor(level, pos) : GrassColor.getDefaultColor();
                        if (!PollutionConfig.POLLUTION_PLANT_TINT_ENABLED.get()) {
                            return base;
                        }
                        return tintGrassColor(level, pos, base);
                    },
                    Blocks.FERN,
                    Blocks.SHORT_GRASS,
                    Blocks.POTTED_FERN
            );

            event.register((state, level, pos, tintIndex) -> {
                        if (!PollutionConfig.POLLUTION_FOLIAGE_TINT_ENABLED.get()) {
                            return level != null && pos != null ? BiomeColors.getAverageFoliageColor(level, pos) : FoliageColor.getDefaultColor();
                        }
                        return tintGrassColor(
                                level,
                                pos,
                                level != null && pos != null ? BiomeColors.getAverageFoliageColor(level, pos) : FoliageColor.getDefaultColor()
                        );
                    },
                    Blocks.OAK_LEAVES,
                    Blocks.JUNGLE_LEAVES,
                    Blocks.ACACIA_LEAVES,
                    Blocks.DARK_OAK_LEAVES,
                    Blocks.MANGROVE_LEAVES,
                    Blocks.VINE
            );
        }
    }

    private static int tintGrassColor(BlockAndTintGetter level, BlockPos pos, int baseColor) {
        if (level == null || pos == null || !PollutionConfig.POLLUTION_ENABLED.get() || !PollutionConfig.POLLUTION_GRASS_TINT_ENABLED.get()) {
            return baseColor;
        }
        double sampledPollution = PollutionClientState.sampleAtBlock(pos);
        if (sampledPollution < PollutionConfig.POLLUTION_VISUAL_EPSILON.get()) {
            return baseColor;
        }

        double normalized = clamp(sampledPollution / PollutionConfig.POLLUTION_GRASS_TINT_MAX.get(), 0.0, 1.0);
        double variation = deterministicNoise(pos.getX(), pos.getZ()) * PollutionConfig.POLLUTION_GRASS_NOISE_STRENGTH.get()
                - PollutionConfig.POLLUTION_GRASS_NOISE_STRENGTH.get() * 0.5;
        normalized = clamp(normalized + variation, 0.0, 1.0);
        double strength = smoothstep(normalized) * PollutionConfig.POLLUTION_GRASS_TINT_STRENGTH.get();
        int target = PollutionConfig.POLLUTION_GRASS_GRADIENT_ENABLED.get()
                ? gradientColor(normalized)
                : HIGH_GRASS_COLOR;
        return blendRgb(baseColor, target, strength);
    }

    private static double fogStrength() {
        if (!PollutionClientState.isPollutionFogActive()) {
            return 0.0;
        }
        double pollution = PollutionClientState.currentFogPollution();
        return smoothstep(clamp(pollution / PollutionConfig.POLLUTION_FOG_MAX.get(), 0.0, 1.0));
    }

    private static int gradientColor(double value) {
        if (value < 0.33) {
            return blendRgb(LOW_GRASS_COLOR, MEDIUM_GRASS_COLOR, value / 0.33);
        }
        if (value < 0.66) {
            return blendRgb(MEDIUM_GRASS_COLOR, HIGH_GRASS_COLOR, (value - 0.33) / 0.33);
        }
        return blendRgb(HIGH_GRASS_COLOR, SEVERE_GRASS_COLOR, (value - 0.66) / 0.34);
    }

    private static int blendRgb(int from, int to, double t) {
        int r = (int) Math.round(lerp((from >> 16) & 0xFF, (to >> 16) & 0xFF, t));
        int g = (int) Math.round(lerp((from >> 8) & 0xFF, (to >> 8) & 0xFF, t));
        int b = (int) Math.round(lerp(from & 0xFF, to & 0xFF, t));
        return (r << 16) | (g << 8) | b;
    }

    private static double smoothstep(double value) {
        return value * value * (3.0 - 2.0 * value);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double lerp(double from, double to, double t) {
        return from + (to - from) * t;
    }

    private static double deterministicNoise(int x, int z) {
        int hash = x * 73428767 ^ z * 912367421;
        hash ^= hash >>> 13;
        hash *= 1274126177;
        hash ^= hash >>> 16;
        return (hash & 0xFFFF) / 65535.0;
    }

    private static void scanClientVisualSources(Level level, BlockPos playerPos) {
        if (!PollutionConfig.POLLUTION_CLIENT_ONLY_VISUAL_FALLBACK.get()
                || !PollutionClientState.shouldRunClientFallback()
                || level == null
                || playerPos == null) {
            return;
        }

        int interval = Math.max(1, PollutionConfig.POLLUTION_CLIENT_SOURCE_SCAN_INTERVAL_TICKS.get());
        if (level.getGameTime() % interval != 0L) {
            return;
        }

        int radius = PollutionConfig.effectiveClientSourceScanRadiusChunks();
        int centerChunkX = Math.floorDiv(playerPos.getX(), 16);
        int centerChunkZ = Math.floorDiv(playerPos.getZ(), 16);
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(centerChunkX + dx, centerChunkZ + dz);
                if (chunk == null) {
                    continue;
                }
                scanClientChunkSources(level, chunk);
            }
        }
    }

    private static void scanClientChunkSources(Level level, LevelChunk chunk) {
        for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
            if (blockEntity.isRemoved()) {
                continue;
            }
            double emission = PollutionSourceDetector.emissionRate(level, blockEntity);
            if (emission <= 0.0) {
                continue;
            }
            PollutionClientState.recordClientVisualSource(
                    blockEntity.getBlockPos(),
                    (float) emission,
                    (float) (emission * 25.0)
            );
        }
    }

    private static void processQueuedRerenders() {
        if (!PollutionConfig.POLLUTION_RERENDER_ENABLED.get()) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.levelRenderer == null) {
            return;
        }

        int maxChunks = PollutionConfig.effectiveRerenderMaxChunksPerTick();
        for (int i = 0; i < maxChunks; i++) {
            Long chunkKey = PollutionClientState.pollRerenderChunk();
            if (chunkKey == null) {
                return;
            }
            ChunkPos chunkPos = new ChunkPos(chunkKey);
            minecraft.levelRenderer.setBlocksDirty(
                    chunkPos.getMinBlockX(),
                    minecraft.level.getMinBuildHeight(),
                    chunkPos.getMinBlockZ(),
                    chunkPos.getMaxBlockX(),
                    minecraft.level.getMaxBuildHeight() - 1,
                    chunkPos.getMaxBlockZ()
            );
        }
    }
}
