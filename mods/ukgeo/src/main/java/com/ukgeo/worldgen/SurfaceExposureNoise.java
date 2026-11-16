package com.ukgeo.worldgen;

/**
 * Deterministic value noise for coherent rare surface-mineral patches.
 * Kept free of Minecraft types so unit tests can run without bootstrapping the game.
 */
final class SurfaceExposureNoise {
    private SurfaceExposureNoise() {
    }

    static boolean allowExposure(int worldX, int worldZ, int materialSeed) {
        double n = valueNoise2(worldX * 0.09, worldZ * 0.09, materialSeed);
        n = 0.7 * n + 0.3 * valueNoise2(worldX * 0.23, worldZ * 0.23, materialSeed ^ 0xA5A5A5A5);
        return n > 0.90;
    }

    static double valueNoise2(double x, double z, int seed) {
        int x0 = (int) Math.floor(x);
        int z0 = (int) Math.floor(z);
        int x1 = x0 + 1;
        int z1 = z0 + 1;
        double fx = x - x0;
        double fz = z - z0;
        double sx = fx * fx * (3.0 - 2.0 * fx);
        double sz = fz * fz * (3.0 - 2.0 * fz);
        double n00 = hash01(x0, z0, seed);
        double n10 = hash01(x1, z0, seed);
        double n01 = hash01(x0, z1, seed);
        double n11 = hash01(x1, z1, seed);
        double nx0 = n00 + (n10 - n00) * sx;
        double nx1 = n01 + (n11 - n01) * sx;
        return nx0 + (nx1 - nx0) * sz;
    }

    private static double hash01(int x, int z, int seed) {
        long hash = 0x9e3779b97f4a7c15L;
        hash ^= (long) x * 0xbf58476d1ce4e5b9L;
        hash ^= (long) z * 0x94d049bb133111ebL;
        hash ^= (long) seed * 0x632be59bd9b4e019L;
        hash ^= hash >>> 30;
        hash *= 0xbf58476d1ce4e5b9L;
        hash ^= hash >>> 27;
        hash *= 0x94d049bb133111ebL;
        hash ^= hash >>> 31;
        return (hash & 0xFFFFFFL) / (double) 0xFFFFFFL;
    }
}
