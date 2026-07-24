package com.ukgeo.realtimelocalisedweather.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.ukgeo.realtimelocalisedweather.config.ClientWeatherConfig;
import com.ukgeo.realtimelocalisedweather.weather.client.ClientWeatherManager;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * A coverage-aware variant of vanilla's cloud mesh.  The cell size, texture coordinates,
 * shading and camera transform are the same as LevelRenderer; weather only controls a stable
 * world-space mask, never a second movement system.
 */
public final class LocalisedCloudRenderer {
    private static final float CLOUD_SCALE = 12.0F;
    private static final float CELL_SIZE = 8.0F;
    private static final float CLOUD_THICKNESS = 4.0F;
    private static final float UV_SCALE = 1.0F / 256.0F;
    private static final float EDGE_EPSILON = 1.0F / 1024.0F;

    private LocalisedCloudRenderer() {
    }

    public static void render(PoseStack poseStack, Matrix4f modelViewMatrix, Matrix4f projectionMatrix, float partialTick, double cameraX, double cameraY, double cameraZ) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || minecraft.options.getCloudsType() == CloudStatus.OFF) return;
        float cloudHeight = minecraft.level.effects().getCloudHeight();
        if (Float.isNaN(cloudHeight)) return;

        double drift = (minecraft.level.getGameTime() + partialTick) * 0.03D;
        double cloudX = (cameraX + drift) / CLOUD_SCALE;
        double cloudY = cloudHeight - cameraY + 0.33D;
        double cloudZ = cameraZ / CLOUD_SCALE + 0.33D;
        cloudX -= Mth.floor(cloudX / 2048.0D) * 2048.0D;
        cloudZ -= Mth.floor(cloudZ / 2048.0D) * 2048.0D;
        int baseX = Mth.floor(cloudX);
        int baseZ = Mth.floor(cloudZ);
        var cameraWeather = ClientWeatherManager.sample(BlockPos.containing(cameraX, cameraY, cameraZ));
        float fallbackCoverage = cameraWeather
            .map(sample -> Mth.clamp(sample.interpolatedCloudCover() / 100.0F * ClientWeatherConfig.CLOUD_DENSITY_MULTIPLIER.get().floatValue(), 0.0F, 1.0F))
            .orElse(0.0F);
        boolean visualTest = cameraWeather.map(ClientWeatherManager::isVisualTestSample).orElse(false);
        float fractionX = (float) (cloudX - baseX);
        float fractionY = (float) (cloudY / CLOUD_THICKNESS - Mth.floor(cloudY / CLOUD_THICKNESS)) * CLOUD_THICKNESS;
        float fractionZ = (float) (cloudZ - baseZ);
        float cloudBaseY = Mth.floor((float) cloudY / CLOUD_THICKNESS) * CLOUD_THICKNESS;
        Vec3 cloudColor = minecraft.level.getCloudColor(partialTick);
        int radius = Math.max(3, Math.min(11, Mth.ceil(ClientWeatherConfig.CLOUD_RENDER_DISTANCE_BLOCKS.get() / 96.0F)));
        boolean[][] occupied = new boolean[radius * 2 + 1][radius * 2 + 1];
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                occupied[x + radius][z + radius] = visualTest
                    ? covered(fallbackCoverage, baseX + x * 8, baseZ + z * 8)
                    : occupied(minecraft, baseX + x * 8, baseZ + z * 8, drift, cameraY, fallbackCoverage);
            }
        }

        poseStack.pushPose();
        poseStack.mulPose(modelViewMatrix);
        poseStack.scale(CLOUD_SCALE, 1.0F, CLOUD_SCALE);
        poseStack.translate(-fractionX, fractionY, -fractionZ);
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL);
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                if (!occupied[x + radius][z + radius]) continue;
                // Coverage controls geometry, not an extra transparent texture.  In
                // particular, 100% must be a continuous overcast rather than vanilla's
                // intentionally patchy cloud pattern.  Emit vertical faces only at an
                // actual weather-bank boundary, so adjacent occupied cells form one
                // seamless cloud mass instead of a collection of visible cubes.
                boolean west = x == -radius || !occupied[x - 1 + radius][z + radius];
                boolean east = x == radius || !occupied[x + 1 + radius][z + radius];
                boolean north = z == -radius || !occupied[x + radius][z - 1 + radius];
                boolean south = z == radius || !occupied[x + radius][z + 1 + radius];
                // LevelRenderer uses the floored cloud-grid origin for the texture, while the
                // fractional component is only a translation.  Keeping those separate avoids
                // stretching the normal cloud texture into camera-relative slabs.
                addCell(builder, x * CELL_SIZE, cloudBaseY, z * CELL_SIZE, cloudColor, baseX, baseZ, cloudBaseY > -5.0F, cloudBaseY <= 5.0F, west, east, north, south);
            }
        }
        var mesh = builder.build();
        if (mesh == null) {
            poseStack.popPose();
            return;
        }

        FogRenderer.levelFogColor();
        RenderType type = RenderType.clouds();
        type.setupRenderState();
        // The coverage mask creates exposed volume edges.  Vanilla emits a matching face for
        // each viewing direction; keeping culling off here gives those same edges a visible
        // inside and outside surface when the camera is at cloud height.
        RenderSystem.disableCull();
        VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        buffer.bind();
        buffer.upload(mesh);
        buffer.drawWithShader(poseStack.last().pose(), projectionMatrix, RenderSystem.getShader());
        VertexBuffer.unbind();
        buffer.close();
        RenderSystem.enableCull();
        type.clearRenderState();
        poseStack.popPose();
    }

    private static boolean occupied(Minecraft minecraft, int cloudGridX, int cloudGridZ, double drift, double cameraY, float fallbackCoverage) {
        double worldX = cloudGridX * CLOUD_SCALE - drift;
        double worldZ = cloudGridZ * CLOUD_SCALE;
        var sample = ClientWeatherManager.sample(BlockPos.containing(worldX, cameraY, worldZ));
        float coverage = sample.map(value -> Mth.clamp(value.interpolatedCloudCover() / 100.0F * ClientWeatherConfig.CLOUD_DENSITY_MULTIPLIER.get().floatValue(), 0.0F, 1.0F)).orElse(fallbackCoverage);
        return covered(coverage, cloudGridX, cloudGridZ);
    }

    /**
     * This is the complete cloud map.  It is deliberately independent of Minecraft's
     * patchy vanilla cloud texture: 100% puts a cloud in every grid cell, 0% removes
     * every cell, and values between them remove stable broad banks from that full grid.
     */
    private static boolean covered(float coverage, int cloudGridX, int cloudGridZ) {
        if (coverage >= 0.999F) return true;
        if (coverage <= 0.001F) return false;
        return coverage > coverageNoise(cloudGridX, cloudGridZ);
    }

    /** Low-frequency value noise makes coverage form broad stable banks rather than checkerboards. */
    private static float coverageNoise(int x, int z) {
        int cellX = Math.floorDiv(x, 3);
        int cellZ = Math.floorDiv(z, 3);
        float tx = smooth(Math.floorMod(x, 3) / 3.0F);
        float tz = smooth(Math.floorMod(z, 3) / 3.0F);
        float a = Mth.lerp(tx, hash(cellX, cellZ), hash(cellX + 1, cellZ));
        float b = Mth.lerp(tx, hash(cellX, cellZ + 1), hash(cellX + 1, cellZ + 1));
        return Mth.lerp(tz, a, b);
    }

    private static float smooth(float value) {
        return value * value * (3.0F - 2.0F * value);
    }

    private static float hash(int x, int z) {
        int value = x * 3129871 ^ z * 116129781 ^ x;
        value = value * value * 42317861 + value * 11;
        return (value & Integer.MAX_VALUE) / (float) Integer.MAX_VALUE;
    }

    private static void addCell(BufferBuilder b, float x, float y, float z, Vec3 color, float offsetX, float offsetZ, boolean bottom, boolean top, boolean west, boolean east, boolean north, boolean south) {
        float r = (float) color.x, g = (float) color.y, bl = (float) color.z;
        if (bottom) quad(b, x, y, z + 8, x + 8, y, z, r * .7F, g * .7F, bl * .7F, offsetX, offsetZ, 0, -1, 0);
        if (top) quad(b, x, y + 4 - EDGE_EPSILON, z + 8, x + 8, y + 4 - EDGE_EPSILON, z, r, g, bl, offsetX, offsetZ, 0, 1, 0);
        if (west) sideX(b, x, y, z, -1, r * .9F, g * .9F, bl * .9F, offsetX, offsetZ);
        if (east) sideX(b, x + 8 - EDGE_EPSILON, y, z, 1, r * .9F, g * .9F, bl * .9F, offsetX, offsetZ);
        if (north) sideZ(b, x, y, z, -1, r * .8F, g * .8F, bl * .8F, offsetX, offsetZ);
        if (south) sideZ(b, x, y, z + 8 - EDGE_EPSILON, 1, r * .8F, g * .8F, bl * .8F, offsetX, offsetZ);
    }

    private static void sideX(BufferBuilder b, float x, float y, float z, int normal, float r, float g, float bl, float ox, float oz) {
        // This mirrors vanilla's eight one-block-deep cloud slices.  A single outer wall
        // vanishes when the camera is inside a cloud bank; the slices keep the familiar
        // Minecraft cloud volume visible at the cloud's own height.
        for (int slice = 0; slice < 8; slice++) {
            float plane = normal < 0 ? x + slice : x + slice + 1.0F - EDGE_EPSILON;
            vertex(b, plane, y, z + 8, r, g, bl, ox, oz, normal, 0, 0);
            vertex(b, plane, y + 4, z + 8, r, g, bl, ox, oz, normal, 0, 0);
            vertex(b, plane, y + 4, z, r, g, bl, ox, oz, normal, 0, 0);
            vertex(b, plane, y, z, r, g, bl, ox, oz, normal, 0, 0);
        }
    }

    private static void sideZ(BufferBuilder b, float x, float y, float z, int normal, float r, float g, float bl, float ox, float oz) {
        for (int slice = 0; slice < 8; slice++) {
            float plane = normal < 0 ? z + slice : z + slice + 1.0F - EDGE_EPSILON;
            vertex(b, x, y + 4, plane, r, g, bl, ox, oz, 0, 0, normal);
            vertex(b, x + 8, y + 4, plane, r, g, bl, ox, oz, 0, 0, normal);
            vertex(b, x + 8, y, plane, r, g, bl, ox, oz, 0, 0, normal);
            vertex(b, x, y, plane, r, g, bl, ox, oz, 0, 0, normal);
        }
    }

    private static void quad(BufferBuilder b, float x1, float y1, float z1, float x2, float y2, float z2, float r, float g, float bl, float ox, float oz, int nx, int ny, int nz) {
        vertex(b, x1, y1, z1, r, g, bl, ox, oz, nx, ny, nz); vertex(b, x2, y2, z1, r, g, bl, ox, oz, nx, ny, nz); vertex(b, x2, y2, z2, r, g, bl, ox, oz, nx, ny, nz); vertex(b, x1, y1, z2, r, g, bl, ox, oz, nx, ny, nz);
    }

    private static void vertex(BufferBuilder b, float x, float y, float z, float r, float g, float bl, float ox, float oz, int nx, int ny, int nz) {
        b.addVertex(x, y, z).setUv(x * UV_SCALE + ox * UV_SCALE, z * UV_SCALE + oz * UV_SCALE).setColor(r, g, bl, 1.0F).setNormal(nx, ny, nz);
    }
}
