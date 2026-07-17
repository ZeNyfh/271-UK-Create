package com.ukgeo.realtimelocalisedweather.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.ukgeo.realtimelocalisedweather.config.ClientWeatherConfig;
import com.ukgeo.realtimelocalisedweather.weather.client.ClientWeatherManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import org.joml.Matrix4f;

public final class LocalisedCloudRenderer {
    private LocalisedCloudRenderer() {
    }

    public static void render(PoseStack poseStack, float partialTick, double cameraX, double cameraY, double cameraZ) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || minecraft.options.getCloudsType() == net.minecraft.client.CloudStatus.OFF) {
            return;
        }
        int renderDistance = ClientWeatherConfig.CLOUD_RENDER_DISTANCE_BLOCKS.get();
        float densityMultiplier = ClientWeatherConfig.CLOUD_DENSITY_MULTIPLIER.get().floatValue();
        poseStack.pushPose();
        Matrix4f matrix = poseStack.last().pose();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        var builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (int dz = -renderDistance; dz <= renderDistance; dz += 32) {
            for (int dx = -renderDistance; dx <= renderDistance; dx += 32) {
                BlockPos samplePos = new BlockPos((int) Math.floor(cameraX + dx), (int) Math.floor(cameraY), (int) Math.floor(cameraZ + dz));
                var sample = ClientWeatherManager.sample(samplePos);
                if (sample.isEmpty()) {
                    continue;
                }
                float cloudCover = sample.get().interpolatedCloudCover() / 100.0F * densityMultiplier;
                if (cloudCover < 0.15F) {
                    continue;
                }
                float stormDarkness = sample.get().snapshot().resolvedPrecipitation().supportsThunder() ? 0.45F : 0.0F;
                float alpha = Math.min(0.7F, 0.18F + cloudCover * 0.35F);
                int colour = (int) (220.0F - stormDarkness * 120.0F - cloudCover * 40.0F);
                float baseX = (float) (samplePos.getX() - cameraX);
                float baseZ = (float) (samplePos.getZ() - cameraZ);
                float windOffsetX = sample.get().snapshot().windSpeedKmh() * 0.02F * partialTick;
                float windOffsetZ = sample.get().snapshot().windDirectionDegrees() / 360.0F * 8.0F * partialTick;
                float y = 140.0F + (1.0F - cloudCover) * 30.0F - stormDarkness * 20.0F - (float) cameraY;
                addCloudQuad(builder, matrix, baseX + windOffsetX, y, baseZ + windOffsetZ, 28.0F, colour, colour, colour, (int) (alpha * 255.0F));
                if (cloudCover > 0.5F) {
                    addCloudQuad(builder, matrix, baseX + 8.0F + windOffsetX, y - 4.0F, baseZ + 8.0F + windOffsetZ, 20.0F, colour - 12, colour - 12, colour - 12, (int) (alpha * 255.0F));
                }
            }
        }
        BufferUploader.drawWithShader(builder.buildOrThrow());
        RenderSystem.disableBlend();
        poseStack.popPose();
    }

    private static void addCloudQuad(
        com.mojang.blaze3d.vertex.BufferBuilder builder,
        Matrix4f matrix,
        float x,
        float y,
        float z,
        float size,
        int red,
        int green,
        int blue,
        int alpha
    ) {
        builder.addVertex(matrix, x, y, z).setColor(red, green, blue, alpha);
        builder.addVertex(matrix, x + size, y, z).setColor(red, green, blue, alpha);
        builder.addVertex(matrix, x + size, y, z + size).setColor(red, green, blue, alpha);
        builder.addVertex(matrix, x, y, z + size).setColor(red, green, blue, alpha);
    }
}
