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
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import org.joml.Matrix4f;

/**
 * Uses Minecraft's own cloud render type and cloud texture.  Coverage decides which of the
 * normal-sized cloud patches are present; it never offsets a patch independently of the world.
 */
public final class LocalisedCloudRenderer {
    private static final int CELL_SIZE = 96;
    private static final int THICKNESS = 4;
    private static final float CLOUD_UV_SCALE = 1.0F / 256.0F;

    private LocalisedCloudRenderer() {
    }

    public static void render(PoseStack poseStack, float partialTick, double cameraX, double cameraY, double cameraZ) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || minecraft.options.getCloudsType() == net.minecraft.client.CloudStatus.OFF) return;

        int distance = ClientWeatherConfig.CLOUD_RENDER_DISTANCE_BLOCKS.get();
        float densityMultiplier = ClientWeatherConfig.CLOUD_DENSITY_MULTIPLIER.get().floatValue();
        int originX = Math.floorDiv((int) Math.floor(cameraX), CELL_SIZE) * CELL_SIZE;
        int originZ = Math.floorDiv((int) Math.floor(cameraZ), CELL_SIZE) * CELL_SIZE;
        Matrix4f matrix = poseStack.last().pose();
        var builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL);
        boolean rendered = false;

        for (int dz = -distance; dz <= distance; dz += CELL_SIZE) {
            for (int dx = -distance; dx <= distance; dx += CELL_SIZE) {
                int worldX = originX + dx;
                int worldZ = originZ + dz;
                var sample = ClientWeatherManager.sample(new BlockPos(worldX, (int) cameraY, worldZ));
                if (sample.isEmpty()) continue;
                float coverage = Math.min(1.0F, sample.get().interpolatedCloudCover() / 100.0F * densityMultiplier);
                if (coverage < 0.02F || cloudHash(worldX, worldZ) > coverage) continue;

                float storm = sample.get().snapshot().resolvedPrecipitation().supportsThunder() ? 0.45F : 0.0F;
                float shade = 0.88F - coverage * 0.14F - storm * 0.42F;
                addCloudPatch(builder, matrix, (float) (worldX - cameraX), 192.0F - storm * 18.0F - (float) cameraY, (float) (worldZ - cameraZ), shade);
                rendered = true;
            }
        }

        var mesh = builder.build();
        if (rendered && mesh != null) {
            RenderType cloudType = RenderType.clouds();
            cloudType.setupRenderState();
            BufferUploader.drawWithShader(mesh);
            cloudType.clearRenderState();
        }
    }

    private static void addCloudPatch(com.mojang.blaze3d.vertex.BufferBuilder builder, Matrix4f matrix, float x, float y, float z, float shade) {
        float edge = CELL_SIZE;
        face(builder, matrix, x, y, z, x + edge, y, z + edge, shade, 0.0F, 1.0F, 0.0F);
        face(builder, matrix, x, y - THICKNESS, z, x + edge, y - THICKNESS, z, shade * 0.7F, 0.0F, -1.0F, 0.0F);
        side(builder, matrix, x, y, z, x + edge, y - THICKNESS, z, shade * 0.9F, 0.0F, 0.0F, -1.0F);
        side(builder, matrix, x + edge, y, z + edge, x, y - THICKNESS, z + edge, shade * 0.72F, 0.0F, 0.0F, 1.0F);
        side(builder, matrix, x, y, z + edge, x, y - THICKNESS, z, shade * 0.8F, -1.0F, 0.0F, 0.0F);
        side(builder, matrix, x + edge, y, z, x + edge, y - THICKNESS, z + edge, shade * 0.84F, 1.0F, 0.0F, 0.0F);
    }

    private static void face(com.mojang.blaze3d.vertex.BufferBuilder builder, Matrix4f matrix, float x1, float y1, float z1, float x2, float y2, float z2, float shade, float normalX, float normalY, float normalZ) {
        vertex(builder, matrix, x1, y1, z1, shade, normalX, normalY, normalZ);
        vertex(builder, matrix, x2, y2, z1, shade, normalX, normalY, normalZ);
        vertex(builder, matrix, x2, y2, z2, shade, normalX, normalY, normalZ);
        vertex(builder, matrix, x1, y1, z2, shade, normalX, normalY, normalZ);
    }

    private static void side(com.mojang.blaze3d.vertex.BufferBuilder builder, Matrix4f matrix, float topX1, float topY, float topZ1, float bottomX2, float bottomY, float bottomZ2, float shade, float normalX, float normalY, float normalZ) {
        vertex(builder, matrix, topX1, topY, topZ1, shade, normalX, normalY, normalZ);
        vertex(builder, matrix, bottomX2, bottomY, bottomZ2, shade, normalX, normalY, normalZ);
        vertex(builder, matrix, bottomX2, topY, bottomZ2, shade, normalX, normalY, normalZ);
        vertex(builder, matrix, topX1, bottomY, topZ1, shade, normalX, normalY, normalZ);
    }

    private static void vertex(com.mojang.blaze3d.vertex.BufferBuilder builder, Matrix4f matrix, float x, float y, float z, float shade, float normalX, float normalY, float normalZ) {
        builder.addVertex(matrix, x, y, z).setUv(x * CLOUD_UV_SCALE, z * CLOUD_UV_SCALE).setColor(shade, shade, shade, 0.8F).setNormal(normalX, normalY, normalZ);
    }

    private static float cloudHash(int x, int z) {
        int value = x * 3129871 ^ z * 116129781 ^ x;
        value = value * value * 42317861 + value * 11;
        return (value & 0x7fffffff) / (float) Integer.MAX_VALUE;
    }
}
