package com.ukgeo.realtimelocalisedweather.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.ukgeo.realtimelocalisedweather.config.ClientWeatherConfig;
import com.ukgeo.realtimelocalisedweather.weather.ResolvedPrecipitation;
import com.ukgeo.realtimelocalisedweather.weather.client.ClientWeatherManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import org.joml.Matrix4f;

public final class LocalisedPrecipitationRenderer {
    private LocalisedPrecipitationRenderer() {
    }

    public static void render(float partialTick, double cameraX, double cameraY, double cameraZ) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        int renderDistance = ClientWeatherConfig.PRECIPITATION_RENDER_DISTANCE_BLOCKS.get();
        RenderSystem.enableBlend();
        try {
            Matrix4f modelView = RenderSystem.getModelViewMatrix();
            var builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            boolean hasPrecipitationColumns = false;
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            for (int dz = -renderDistance; dz <= renderDistance; dz += 6) {
                for (int dx = -renderDistance; dx <= renderDistance; dx += 6) {
                    BlockPos samplePos = new BlockPos((int) Math.floor(cameraX + dx), (int) Math.floor(cameraY), (int) Math.floor(cameraZ + dz));
                    var sample = ClientWeatherManager.sample(samplePos);
                    if (sample.isEmpty()) {
                        continue;
                    }
                    var weather = sample.get();
                    if (!weather.snapshot().resolvedPrecipitation().isPrecipitating()) {
                        continue;
                    }
                    if (!minecraft.level.canSeeSky(samplePos.above())) {
                        continue;
                    }
                    float baseIntensity = Math.max(0.1F, weather.interpolatedRate() / 6.0F);
                    float densityMultiplier = weather.snapshot().resolvedPrecipitation().isSnowy()
                        ? ClientWeatherConfig.SNOW_DENSITY_MULTIPLIER.get().floatValue()
                        : ClientWeatherConfig.PRECIPITATION_DENSITY_MULTIPLIER.get().floatValue();
                    if (baseIntensity * densityMultiplier <= 0.02F) {
                        continue;
                    }
                    int topY = minecraft.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, samplePos.getX(), samplePos.getZ());
                    float slant = ClientWeatherConfig.ENABLE_WIND_SLANT.get() ? weather.snapshot().windSpeedKmh() / 100.0F : 0.0F;
                    float length = weather.snapshot().resolvedPrecipitation().isSnowy() ? 4.0F : 8.0F;
                    float x = (float) (samplePos.getX() + 0.5D - cameraX);
                    float y1 = (float) (topY + 8 - cameraY);
                    float y2 = y1 - length;
                    float z = (float) (samplePos.getZ() + 0.5D - cameraZ);
                    float alpha = Math.min(0.8F, baseIntensity * densityMultiplier * 0.45F);
                    int red = weather.snapshot().resolvedPrecipitation().isSnowy() ? 240 : 160;
                    int green = weather.snapshot().resolvedPrecipitation().isSnowy() ? 240 : 180;
                    int blue = 255;
                    addColumn(builder, modelView, x, y1, y2, z, slant, red, green, blue, (int) (alpha * 255.0F));
                    hasPrecipitationColumns = true;
                    if (weather.snapshot().resolvedPrecipitation() == ResolvedPrecipitation.HAIL) {
                        addColumn(builder, modelView, x + 0.15F, y1 - 1.0F, y2 - 1.0F, z + 0.15F, slant * 0.6F, 220, 220, 240, 220);
                    }
                }
            }
            var meshData = builder.build();
            if (hasPrecipitationColumns && meshData != null) {
                BufferUploader.drawWithShader(meshData);
            }
        } finally {
            RenderSystem.disableBlend();
        }
    }

    private static void addColumn(
        com.mojang.blaze3d.vertex.BufferBuilder builder,
        Matrix4f matrix,
        float x,
        float y1,
        float y2,
        float z,
        float slant,
        int red,
        int green,
        int blue,
        int alpha
    ) {
        float width = 0.08F;
        builder.addVertex(matrix, x - width, y1, z).setColor(red, green, blue, alpha);
        builder.addVertex(matrix, x + width, y1, z).setColor(red, green, blue, alpha);
        builder.addVertex(matrix, x + width + slant, y2, z + slant).setColor(red, green, blue, 0);
        builder.addVertex(matrix, x - width + slant, y2, z + slant).setColor(red, green, blue, 0);
    }
}
