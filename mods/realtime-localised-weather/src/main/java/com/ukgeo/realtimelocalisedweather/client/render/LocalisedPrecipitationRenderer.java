package com.ukgeo.realtimelocalisedweather.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.ukgeo.realtimelocalisedweather.config.ClientWeatherConfig;
import com.ukgeo.realtimelocalisedweather.weather.client.ClientWeatherManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

/** Renders the vanilla rain/snow textures with a local, continuously animated intensity. */
public final class LocalisedPrecipitationRenderer {
    private static final ResourceLocation RAIN_TEXTURE = ResourceLocation.withDefaultNamespace("textures/environment/rain.png");
    private static final ResourceLocation SNOW_TEXTURE = ResourceLocation.withDefaultNamespace("textures/environment/snow.png");
    private static final int COLUMN_SPACING = 4;

    private LocalisedPrecipitationRenderer() {
    }

    public static void render(float partialTick, double cameraX, double cameraY, double cameraZ) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) return;

        int renderDistance = ClientWeatherConfig.PRECIPITATION_RENDER_DISTANCE_BLOCKS.get();
        Matrix4f modelView = RenderSystem.getModelViewMatrix();
        float weatherTime = minecraft.level.getGameTime() + partialTick;
        RenderSystem.enableBlend();
        RenderSystem.disableCull();
        try {
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
            var rainBuilder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            var snowBuilder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            boolean hasRain = false;
            boolean hasSnow = false;
            for (int dz = -renderDistance; dz <= renderDistance; dz += COLUMN_SPACING) {
                for (int dx = -renderDistance; dx <= renderDistance; dx += COLUMN_SPACING) {
                    BlockPos position = new BlockPos((int) Math.floor(cameraX + dx), (int) Math.floor(cameraY), (int) Math.floor(cameraZ + dz));
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

                    int groundY = minecraft.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, position.getX(), position.getZ());
                    float top = (float) (Math.min(cameraY + 14.0D, groundY + 28) - cameraY);
                    float bottom = Math.max((float) (groundY - cameraY), top - (snow ? 10.0F : 18.0F));
                    float wind = ClientWeatherConfig.ENABLE_WIND_SLANT.get() ? weather.snapshot().windSpeedKmh() / 300.0F : 0.0F;
                    float x = (float) (position.getX() + 0.5D - cameraX);
                    float z = (float) (position.getZ() + 0.5D - cameraZ);
                    int alpha = (int) (Math.min(0.9F, 0.20F + intensity * 0.70F) * 255.0F);

                    var builder = snow ? snowBuilder : rainBuilder;
                    addFallingColumn(builder, modelView, x, top, bottom, z, wind, snow, weatherTime, position.getX(), position.getZ(), alpha);
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
        }
    }

    private static void addFallingColumn(com.mojang.blaze3d.vertex.BufferBuilder builder, Matrix4f matrix, float x, float top, float bottom, float z, float wind, boolean snow, float weatherTime, int worldX, int worldZ, int alpha) {
        float width = snow ? 0.35F : 0.18F;
        float phase = (weatherTime + hash(worldX, worldZ)) * (snow ? 0.0125F : 0.075F);
        float v = phase - (float) Math.floor(phase);
        float u = (hash(worldZ, worldX) & 15) / 16.0F;
        builder.addVertex(matrix, x - width, top, z).setUv(u, v).setColor(255, 255, 255, alpha);
        builder.addVertex(matrix, x + width, top, z).setUv(u + 0.0625F, v).setColor(255, 255, 255, alpha);
        builder.addVertex(matrix, x + width + wind, bottom, z + wind).setUv(u + 0.0625F, v + 1.0F).setColor(255, 255, 255, alpha);
        builder.addVertex(matrix, x - width + wind, bottom, z + wind).setUv(u, v + 1.0F).setColor(255, 255, 255, alpha);
        builder.addVertex(matrix, x, top, z - width).setUv(u, v).setColor(255, 255, 255, alpha);
        builder.addVertex(matrix, x, top, z + width).setUv(u + 0.0625F, v).setColor(255, 255, 255, alpha);
        builder.addVertex(matrix, x + wind, bottom, z + width + wind).setUv(u + 0.0625F, v + 1.0F).setColor(255, 255, 255, alpha);
        builder.addVertex(matrix, x + wind, bottom, z - width + wind).setUv(u, v + 1.0F).setColor(255, 255, 255, alpha);
    }

    private static float densityHash(int x, int z) {
        return (hash(x, z) & 0x7fffffff) / (float) Integer.MAX_VALUE;
    }

    private static int hash(int x, int z) {
        int value = x * 3129871 ^ z * 116129781 ^ x;
        return value * value * 42317861 + value * 11;
    }
}
