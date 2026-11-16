package com.ukgeo.realtimelocalisedweather.client.render;

/**
 * Float-safe precipitation UV animation helpers.
 * <p>
 * Animation time must never be added to an unbounded world-coordinate hash in float
 * space — large hashes destroy the mantissa bits needed for per-frame scroll.
 */
public final class PrecipitationAnimation {
    /** Texture cycles per game tick for rain (independent of intensity). */
    public static final float RAIN_SPEED = 0.075F;
    /** Texture cycles per game tick for snow (slower than rain). */
    public static final float SNOW_SPEED = 0.0125F;
    /**
     * Game-time period that keeps {@code period * RAIN_SPEED} and {@code period * SNOW_SPEED}
     * exact integers so wraparound does not hitch the fractional UV phase.
     */
    public static final long WEATHER_TIME_PERIOD = 1600L;

    private PrecipitationAnimation() {
    }

    /** Smooth animation clock from level game time + frame partial tick. */
    public static float weatherTime(long gameTime, float partialTick) {
        long wrapped = Math.floorMod(gameTime, WEATHER_TIME_PERIOD);
        return wrapped + partialTick;
    }

    /** Deterministic per-column phase offset in {@code [0, 1)}. */
    public static float phaseOffset(int worldX, int worldZ) {
        return (hash(worldX, worldZ) & 0xFFFF) / 65536.0F;
    }

    /** Animation speed for the given precipitation type (not intensity-dependent). */
    public static float speed(boolean snow) {
        return snow ? SNOW_SPEED : RAIN_SPEED;
    }

    /**
     * Fractional UV scroll phase in {@code [0, 1)}.
     * Advances with {@code weatherTime} only; hash contributes a bounded offset.
     */
    public static float scrollPhase(float weatherTime, int worldX, int worldZ, boolean snow) {
        double phase = (double) weatherTime * speed(snow) + phaseOffset(worldX, worldZ);
        return (float) (phase - Math.floor(phase));
    }

    /**
     * Vanilla-style vertical UV for a column edge: world Y tiling plus animated scroll.
     * Higher geometric Y maps to a larger V so scrolling the phase makes streaks fall down.
     */
    public static float verticalUv(float worldY, float scrollPhase) {
        return worldY * 0.25F + scrollPhase;
    }

    /** Texture U origin (one of 16 vertical strips) for variety across columns. */
    public static float textureU(int worldX, int worldZ) {
        return (hash(worldZ, worldX) & 15) / 16.0F;
    }

    public static int hash(int x, int z) {
        int value = x * 3129871 ^ z * 116129781 ^ x;
        return value * value * 42317861 + value * 11;
    }
}
