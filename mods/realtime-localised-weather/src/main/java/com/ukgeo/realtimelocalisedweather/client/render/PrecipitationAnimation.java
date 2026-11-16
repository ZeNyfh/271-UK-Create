package com.ukgeo.realtimelocalisedweather.client.render;

/** Float-safe UV animation matching vanilla LevelRenderer rain/snow scroll. */
public final class PrecipitationAnimation {
    /** Vanilla rain uses ticks & 131071 before /32 — keep bounded. */
    public static final int RAIN_TICK_MASK = 131071;
    public static final int SNOW_TICK_MASK = 511;

    private PrecipitationAnimation() {}

    public static int hash(int x, int z) {
        int value = x * 3129871 ^ z * 116129781 ^ x;
        return value * value * 42317861 + value * 11;
    }

    /** Vanilla column seed: x*x*3121 + x*45238971 ^ z*z*418711 + z*13761 */
    public static int columnSeed(int worldX, int worldZ) {
        return worldX * worldX * 3121 + worldX * 45238971 ^ worldZ * worldZ * 418711 + worldZ * 13761;
    }

    /** Deterministic unit float in [0,1) from column seed. */
    public static float columnUnit(int worldX, int worldZ) {
        return (columnSeed(worldX, worldZ) & 0xFF) / 256.0F;
    }

    /**
     * Vanilla rain V scroll (f4). Float-safe: ticks masked; column phase only 8 bits.
     * {@code gameTime} + {@code partialTick} advance animation; intensity must not affect this.
     */
    public static float rainScrollV(long gameTime, float partialTick, int worldX, int worldZ) {
        int ticks = (int) (gameTime & RAIN_TICK_MASK);
        int colPhase = columnSeed(worldX, worldZ) & 0xFF;
        float speed = 3.0F + columnUnit(worldX, worldZ); // 3..4 like vanilla 3+nextFloat
        float scroll = -((ticks + colPhase) + partialTick) / 32.0F * speed;
        return floorMod(scroll, 32.0F);
    }

    /** Vanilla snow base V scroll (f8). */
    public static float snowScrollV(long gameTime, float partialTick) {
        int ticks = (int) (gameTime & SNOW_TICK_MASK);
        float scroll = -((ticks) + partialTick) / 512.0F;
        return floorMod(scroll, 1.0F);
    }

    /** Extra snow V jitter (f10-ish), small and deterministic. */
    public static float snowScrollVJitter(int worldX, int worldZ) {
        return (hash(worldX, worldZ) & 0xFF) / 256.0F * 0.01F;
    }

    /** Snow U drift base (f9 without time) — keep mild deterministic offset. */
    public static float snowUOffset(int worldX, int worldZ) {
        return columnUnit(worldX, worldZ);
    }

    public static float floorMod(float value, float modulus) {
        return value - modulus * (float) Math.floor(value / modulus);
    }

    /**
     * Half-extent of the rain quad in XZ, matching vanilla rainSize tables:
     * perpendicular to the vector from camera to column, length 0.5.
     * Returns float[2] = {d0, d1} used as ± offsets on X and Z.
     */
    public static float[] quadHalfExtent(double cameraRelX, double cameraRelZ) {
        float len = (float) Math.sqrt(cameraRelX * cameraRelX + cameraRelZ * cameraRelZ);
        if (len < 1.0e-4F) {
            return new float[] {0.5F, 0.0F};
        }
        // vanilla: rainSizeX = -dz/len, rainSizeZ = dx/len, then * 0.5
        float d0 = (float) (-cameraRelZ / len) * 0.5F;
        float d1 = (float) (cameraRelX / len) * 0.5F;
        return new float[] {d0, d1};
    }

    public static float verticalUv(float worldY, float scroll) {
        return worldY * 0.25F + scroll;
    }
}
