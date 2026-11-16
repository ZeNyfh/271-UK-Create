package com.ukgeo.realtimelocalisedweather.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.ukgeo.realtimelocalisedweather.config.ClientWeatherConfig;
import com.ukgeo.realtimelocalisedweather.weather.client.ClientWeatherManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Renders vanilla-matching rain/snow quads with localised intensity sampling.
 * One camera-facing quad per block column; UV scroll is float-safe and intensity-independent.
 */
public final class LocalisedPrecipitationRenderer {
    private static final ResourceLocation RAIN_TEXTURE = ResourceLocation.withDefaultNamespace("textures/environment/rain.png");
    private static final ResourceLocation SNOW_TEXTURE = ResourceLocation.withDefaultNamespace("textures/environment/snow.png");

    private LocalisedPrecipitationRenderer() {
    }

    public static void render(LightTexture lightTexture, float partialTick, double cameraX, double cameraY, double cameraZ) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) return;

        // Cap to vanilla visual radius (config defaults were compensating for spacing-4 sparsity).
        int radius = Math.min(
            ClientWeatherConfig.PRECIPITATION_RENDER_DISTANCE_BLOCKS.get(),
            Minecraft.useFancyGraphics() ? 10 : 5
        );
        long gameTime = minecraft.level.getGameTime();

        lightTexture.turnOnLightLayer();
        RenderSystem.enableBlend();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(Minecraft.useShaderTransparency());
        RenderSystem.disableCull();
        try {
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShader(GameRenderer::getParticleShader);
            var rainBuilder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
            var snowBuilder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
            boolean hasRain = false;
            boolean hasSnow = false;

            int camBlockX = Mth.floor(cameraX);
            int camBlockY = Mth.floor(cameraY);
            int camBlockZ = Mth.floor(cameraZ);
            BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();

            for (int z = camBlockZ - radius; z <= camBlockZ + radius; z++) {
                for (int x = camBlockX - radius; x <= camBlockX + radius; x++) {
                    mutable.set(x, camBlockY, z);
                    var sample = ClientWeatherManager.sample(mutable);
                    if (sample.isEmpty() || !minecraft.level.canSeeSky(mutable.above())) continue;

                    var weather = sample.get();
                    if (!weather.snapshot().hasPrecipitation() && weather.interpolatedRate() <= 0.001F) continue;
                    boolean snow = weather.snapshot().resolvedPrecipitation().isSnowy();
                    float multiplier = snow
                        ? ClientWeatherConfig.SNOW_DENSITY_MULTIPLIER.get().floatValue()
                        : ClientWeatherConfig.PRECIPITATION_DENSITY_MULTIPLIER.get().floatValue();
                    float intensity = Math.min(1.0F, (float) Math.sqrt(Math.max(0.0F, weather.interpolatedRate())) / 3.0F) * multiplier;
                    if (intensity < 0.03F || densityHash(x, z) > intensity) continue;

                    int groundY = minecraft.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                    // Vanilla: j2 = max(camY - radius, ground), k2 = max(camY + radius, ground)
                    int yBottom = camBlockY - radius;
                    if (yBottom < groundY) {
                        yBottom = groundY;
                    }
                    int yTop = camBlockY + radius;
                    if (yTop < groundY) {
                        yTop = groundY;
                    }
                    if (yBottom == yTop) continue;

                    double relX = x + 0.5D - cameraX;
                    double relZ = z + 0.5D - cameraZ;
                    float distNorm = (float) Math.sqrt(relX * relX + relZ * relZ) / (float) radius;
                    float alpha = ((1.0F - distNorm * distNorm) * 0.5F + 0.5F)
                        * Math.min(1.0F, 0.25F + intensity * 0.75F);
                    if (alpha <= 0.01F) continue;

                    float wind = ClientWeatherConfig.ENABLE_WIND_SLANT.get()
                        ? weather.snapshot().windSpeedKmh() / 300.0F
                        : 0.0F;
                    int light = LevelRenderer.getLightColor(minecraft.level, mutable.set(x, Math.max(groundY, camBlockY), z));

                    var builder = snow ? snowBuilder : rainBuilder;
                    addColumnQuad(
                        builder, relX, relZ, yBottom, yTop, cameraY, wind, snow,
                        gameTime, partialTick, x, z, alpha, light
                    );
                    if (snow) hasSnow = true;
                    else hasRain = true;
                }
            }

            var rainMesh = rainBuilder.build();
            if (hasRain && rainMesh != null) {
                RenderSystem.setShaderTexture(0, RAIN_TEXTURE);
                BufferUploader.drawWithShader(rainMesh);
            }
            var snowMesh = snowBuilder.build();
            if (hasSnow && snowMesh != null) {
                RenderSystem.setShaderTexture(0, SNOW_TEXTURE);
                BufferUploader.drawWithShader(snowMesh);
            }
        } finally {
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
            lightTexture.turnOffLightLayer();
        }
    }

    /**
     * Single camera-facing quad (vanilla rainSize orientation). Vertices are camera-relative
     * and not pre-multiplied by ModelView — the particle shader applies it once.
     * Wind only shifts bottom X/Z; falling comes from UV V scroll.
     */
    private static void addColumnQuad(
        BufferBuilder builder,
        double relX,
        double relZ,
        int bottomY,
        int topY,
        double cameraY,
        float wind,
        boolean snow,
        long gameTime,
        float partialTick,
        int worldX,
        int worldZ,
        float alpha,
        int light
    ) {
        float[] ext = PrecipitationAnimation.quadHalfExtent(relX, relZ);
        float d0 = ext[0];
        float d1 = ext[1];
        float top = (float) (topY - cameraY);
        float bottom = (float) (bottomY - cameraY);

        float scroll = snow
            ? PrecipitationAnimation.snowScrollV(gameTime, partialTick)
                + PrecipitationAnimation.snowScrollVJitter(worldX, worldZ)
            : PrecipitationAnimation.rainScrollV(gameTime, partialTick, worldX, worldZ);
        // Vanilla: top verts use bottom world Y; bottom verts use top world Y
        float vTop = PrecipitationAnimation.verticalUv(bottomY, scroll);
        float vBottom = PrecipitationAnimation.verticalUv(topY, scroll);
        float u0 = snow ? PrecipitationAnimation.snowUOffset(worldX, worldZ) : 0.0F;
        float u1 = u0 + 1.0F;
        float bx = wind;
        float bz = wind;

        builder.addVertex((float) (relX - d0), top, (float) (relZ - d1))
            .setUv(u0, vTop).setColor(1.0F, 1.0F, 1.0F, alpha).setLight(light);
        builder.addVertex((float) (relX + d0), top, (float) (relZ + d1))
            .setUv(u1, vTop).setColor(1.0F, 1.0F, 1.0F, alpha).setLight(light);
        builder.addVertex((float) (relX + d0 + bx), bottom, (float) (relZ + d1 + bz))
            .setUv(u1, vBottom).setColor(1.0F, 1.0F, 1.0F, alpha).setLight(light);
        builder.addVertex((float) (relX - d0 + bx), bottom, (float) (relZ - d1 + bz))
            .setUv(u0, vBottom).setColor(1.0F, 1.0F, 1.0F, alpha).setLight(light);
    }

    /** Exposed for density diagnostics / tests — same thresholding as the renderer. */
    public static boolean passesDensityFilter(int worldX, int worldZ, float intensity) {
        return intensity >= 0.03F && densityHash(worldX, worldZ) <= intensity;
    }

    public static float densityHash(int x, int z) {
        return (PrecipitationAnimation.hash(x, z) & 0x7fffffff) / (float) Integer.MAX_VALUE;
    }
}
