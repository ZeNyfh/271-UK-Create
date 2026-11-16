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
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Renders vanilla rain/snow textures with local, continuously animated intensity.
 * Positions are camera-relative world anchors; UV scroll is driven by a float-safe
 * animation clock so streaks keep falling while the player stands still.
 */
public final class LocalisedPrecipitationRenderer {
    private static final ResourceLocation RAIN_TEXTURE = ResourceLocation.withDefaultNamespace("textures/environment/rain.png");
    private static final ResourceLocation SNOW_TEXTURE = ResourceLocation.withDefaultNamespace("textures/environment/snow.png");
    private static final int COLUMN_SPACING = 4;

    private LocalisedPrecipitationRenderer() {
    }

    public static void render(LightTexture lightTexture, float partialTick, double cameraX, double cameraY, double cameraZ) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) return;

        int renderDistance = ClientWeatherConfig.PRECIPITATION_RENDER_DISTANCE_BLOCKS.get();
        float weatherTime = PrecipitationAnimation.weatherTime(minecraft.level.getGameTime(), partialTick);

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

            for (int dz = -renderDistance; dz <= renderDistance; dz += COLUMN_SPACING) {
                for (int dx = -renderDistance; dx <= renderDistance; dx += COLUMN_SPACING) {
                    BlockPos position = new BlockPos(
                        (int) Math.floor(cameraX + dx),
                        (int) Math.floor(cameraY),
                        (int) Math.floor(cameraZ + dz)
                    );
                    var sample = ClientWeatherManager.sample(position);
                    if (sample.isEmpty() || !minecraft.level.canSeeSky(position.above())) continue;

                    var weather = sample.get();
                    if (!weather.snapshot().hasPrecipitation() && weather.interpolatedRate() <= 0.001F) continue;
                    boolean snow = weather.snapshot().resolvedPrecipitation().isSnowy();
                    float multiplier = snow
                        ? ClientWeatherConfig.SNOW_DENSITY_MULTIPLIER.get().floatValue()
                        : ClientWeatherConfig.PRECIPITATION_DENSITY_MULTIPLIER.get().floatValue();
                    float intensity = Math.min(1.0F, (float) Math.sqrt(Math.max(0.0F, weather.interpolatedRate())) / 3.0F) * multiplier;
                    if (intensity < 0.03F || densityHash(position.getX(), position.getZ()) > intensity) continue;

                    int groundY = minecraft.level.getHeight(Heightmap.Types.MOTION_BLOCKING, position.getX(), position.getZ());
                    float topWorldY = (float) Math.min(cameraY + 14.0D, groundY + 28);
                    float bottomWorldY = Math.max(groundY, topWorldY - (snow ? 10.0F : 18.0F));
                    float top = (float) (topWorldY - cameraY);
                    float bottom = (float) (bottomWorldY - cameraY);
                    float wind = ClientWeatherConfig.ENABLE_WIND_SLANT.get() ? weather.snapshot().windSpeedKmh() / 300.0F : 0.0F;
                    float x = (float) (position.getX() + 0.5D - cameraX);
                    float z = (float) (position.getZ() + 0.5D - cameraZ);
                    float alpha = Math.min(0.9F, 0.20F + intensity * 0.70F);
                    int light = LevelRenderer.getLightColor(minecraft.level, position.atY(Math.max(groundY, (int) Math.floor(cameraY))));

                    var builder = snow ? snowBuilder : rainBuilder;
                    addFallingColumn(
                        builder, x, top, bottom, z, topWorldY, bottomWorldY, wind, snow, weatherTime,
                        position.getX(), position.getZ(), alpha, light
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
     * Builds two crossed, camera-relative quads. Vertices are not pre-multiplied by
     * the model-view matrix — the particle shader applies ModelViewMat once.
     * Wind only shifts the bottom horizontally; vertical falling comes from UV scroll.
     */
    private static void addFallingColumn(
        BufferBuilder builder,
        float x,
        float top,
        float bottom,
        float z,
        float topWorldY,
        float bottomWorldY,
        float wind,
        boolean snow,
        float weatherTime,
        int worldX,
        int worldZ,
        float alpha,
        int light
    ) {
        float width = snow ? 0.35F : 0.18F;
        float scroll = PrecipitationAnimation.scrollPhase(weatherTime, worldX, worldZ, snow);
        float vTop = PrecipitationAnimation.verticalUv(bottomWorldY, scroll);
        float vBottom = PrecipitationAnimation.verticalUv(topWorldY, scroll);
        float u0 = PrecipitationAnimation.textureU(worldX, worldZ);
        float u1 = u0 + 0.0625F;

        builder.addVertex(x - width, top, z).setUv(u0, vTop).setColor(1.0F, 1.0F, 1.0F, alpha).setLight(light);
        builder.addVertex(x + width, top, z).setUv(u1, vTop).setColor(1.0F, 1.0F, 1.0F, alpha).setLight(light);
        builder.addVertex(x + width + wind, bottom, z + wind).setUv(u1, vBottom).setColor(1.0F, 1.0F, 1.0F, alpha).setLight(light);
        builder.addVertex(x - width + wind, bottom, z + wind).setUv(u0, vBottom).setColor(1.0F, 1.0F, 1.0F, alpha).setLight(light);

        builder.addVertex(x, top, z - width).setUv(u0, vTop).setColor(1.0F, 1.0F, 1.0F, alpha).setLight(light);
        builder.addVertex(x, top, z + width).setUv(u1, vTop).setColor(1.0F, 1.0F, 1.0F, alpha).setLight(light);
        builder.addVertex(x + wind, bottom, z + width + wind).setUv(u1, vBottom).setColor(1.0F, 1.0F, 1.0F, alpha).setLight(light);
        builder.addVertex(x + wind, bottom, z - width + wind).setUv(u0, vBottom).setColor(1.0F, 1.0F, 1.0F, alpha).setLight(light);
    }

    /** Exposed for density diagnostics / tests — same thresholding as the renderer. */
    public static boolean passesDensityFilter(int worldX, int worldZ, float intensity) {
        return intensity >= 0.03F && densityHash(worldX, worldZ) <= intensity;
    }

    public static float densityHash(int x, int z) {
        return (PrecipitationAnimation.hash(x, z) & 0x7fffffff) / (float) Integer.MAX_VALUE;
    }
}
