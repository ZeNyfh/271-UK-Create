package git.zenyfh.vanilla_adjustments;

import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.FoliageColor;
import net.minecraft.world.level.GrassColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

public final class PollutionClientVisuals {
    private static final int DEAD_GRASS_COLOR = 0x8A7A45;
    private static final float FOG_TINT_RED = 0.58F;
    private static final float FOG_TINT_GREEN = 0.54F;
    private static final float FOG_TINT_BLUE = 0.45F;

    private PollutionClientVisuals() {
    }

    @EventBusSubscriber(modid = Vanilla_adjustments.MODID, value = Dist.CLIENT)
    public static final class ForgeBus {
        private ForgeBus() {
        }

        @SubscribeEvent
        public static void onRenderFog(ViewportEvent.RenderFog event) {
            if (!VanillaAdjustConfig.POLLUTION_ENABLED.get() || !VanillaAdjustConfig.POLLUTION_FOG_ENABLED.get()) {
                return;
            }
            double strength = fogStrength();
            if (strength <= 0.001) {
                return;
            }

            float minMultiplier = VanillaAdjustConfig.POLLUTION_FOG_MIN_DISTANCE_MULTIPLIER.get().floatValue();
            float multiplier = (float) lerp(1.0, minMultiplier, strength);
            event.scaleFarPlaneDistance(multiplier);
            event.scaleNearPlaneDistance((float) lerp(1.0, Math.max(0.15F, minMultiplier), strength));
            event.setCanceled(true);
        }

        @SubscribeEvent
        public static void onFogColor(ViewportEvent.ComputeFogColor event) {
            if (!VanillaAdjustConfig.POLLUTION_ENABLED.get()
                    || !VanillaAdjustConfig.POLLUTION_FOG_ENABLED.get()
                    || !VanillaAdjustConfig.POLLUTION_FOG_COLOR_TINT_ENABLED.get()) {
                return;
            }
            double strength = fogStrength() * 0.45;
            if (strength <= 0.001) {
                return;
            }

            event.setRed((float) lerp(event.getRed(), FOG_TINT_RED, strength));
            event.setGreen((float) lerp(event.getGreen(), FOG_TINT_GREEN, strength));
            event.setBlue((float) lerp(event.getBlue(), FOG_TINT_BLUE, strength));
        }
    }

    @EventBusSubscriber(modid = Vanilla_adjustments.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
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
                    Blocks.FERN,
                    Blocks.SHORT_GRASS,
                    Blocks.POTTED_FERN,
                    Blocks.SUGAR_CANE
            );

            event.register((state, level, pos, tintIndex) -> {
                        if (!VanillaAdjustConfig.POLLUTION_FOLIAGE_TINT_ENABLED.get()) {
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

    private static int tintGrassColor(BlockAndTintGetter level, net.minecraft.core.BlockPos pos, int baseColor) {
        if (level == null || pos == null || !VanillaAdjustConfig.POLLUTION_ENABLED.get() || !VanillaAdjustConfig.POLLUTION_GRASS_TINT_ENABLED.get()) {
            return baseColor;
        }
        double strength = smoothstep(clamp(PollutionClientState.currentDisplayPollution() / VanillaAdjustConfig.POLLUTION_GRASS_TINT_MAX.get(), 0.0, 1.0));
        strength *= VanillaAdjustConfig.POLLUTION_GRASS_TINT_STRENGTH.get();
        return blendRgb(baseColor, DEAD_GRASS_COLOR, strength);
    }

    private static double fogStrength() {
        return smoothstep(clamp(PollutionClientState.displayPollution() / VanillaAdjustConfig.POLLUTION_FOG_MAX.get(), 0.0, 1.0));
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
}
