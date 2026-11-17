package com.ukgeo.worldgen;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** One terrain-derived plane per connected lake, independent of chunks and query order. */
final class LakeSurface {
    static final int NODATA = -32768;
    static final int MAX_NEGATIVE_SAMPLES = 65_536;
    private static final long NO_OWNER = Long.MIN_VALUE;
    private static final ShoreCell DRY_CELL = new ShoreCell(NO_OWNER, null);
    private static final int[][] NEIGHBOURS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    @FunctionalInterface
    interface Source {
        /** Original DEM water-body sample, or NODATA for land / river artifacts. */
        int sample(int worldX, int worldZ);
    }

    private final int originX, originZ, cellBlocks, width, depth;
    private final Source source;
    private final Map<Long, Integer> samples = new ConcurrentHashMap<>();
    private final Map<Long, Integer> planes = new ConcurrentHashMap<>();
    // Retain expensive river-artifact rejections too, without a world-sized land cache.
    private final BoundedCache<Long, Boolean> negativeSamples = new BoundedCache<>(MAX_NEGATIVE_SAMPLES);
    // Rasterize once per coarse cell, then all block/shore-distance queries use one lookup.
    private final BoundedCache<Long, ShoreCell> shores = new BoundedCache<>(16_384);

    LakeSurface(int originX, int originZ, int cellBlocks, int widthBlocks, int depthBlocks, Source source) {
        this.originX = originX;
        this.originZ = originZ;
        this.cellBlocks = Math.max(1, cellBlocks);
        this.width = (widthBlocks + this.cellBlocks - 1) / this.cellBlocks;
        this.depth = (depthBlocks + this.cellBlocks - 1) / this.cellBlocks;
        this.source = source;
    }

    /** Round both convex and concave raster corners, retaining the source lake's plane. */
    boolean contains(int x, int z) {
        int cx = Math.floorDiv(x - originX, cellBlocks);
        int cz = Math.floorDiv(z - originZ, cellBlocks);
        if (cx < 0 || cz < 0 || cx >= width || cz >= depth) {
            return false;
        }
        if (cellBlocks == 1) {
            return sample(cx, cz) != NODATA;
        }
        return shoreCell(cx, cz).ownerAt(localIndex(x, z)) != NO_OWNER;
    }

    int waterY(int x, int z) {
        int cx = Math.floorDiv(x - originX, cellBlocks);
        int cz = Math.floorDiv(z - originZ, cellBlocks);
        Integer plane = planes.get(key(cx, cz));
        if (plane != null) {
            return plane;
        }
        if (sample(cx, cz) == NODATA) {
            if (cx < 0 || cz < 0 || cx >= width || cz >= depth || cellBlocks == 1) {
                return NODATA;
            }
            long owner = shoreCell(cx, cz).ownerAt(localIndex(x, z));
            if (owner == NO_OWNER) {
                return NODATA;
            }
            cx = (int) (owner >> 32);
            cz = (int) owner;
            plane = planes.get(owner);
            if (plane != null) {
                return plane;
            }
        }
        return resolvePlane(cx, cz);
    }

    private int localIndex(int x, int z) {
        return Math.floorMod(z - originZ, cellBlocks) * cellBlocks + Math.floorMod(x - originX, cellBlocks);
    }

    private ShoreCell shoreCell(int cx, int cz) {
        long cell = key(cx, cz);
        ShoreCell cached = shores.get(cell);
        if (cached != null) {
            return cached;
        }
        ShoreCell shaped = rasterizeShore(cx, cz);
        ShoreCell existing = shores.putIfAbsent(cell, shaped);
        return existing == null ? shaped : existing;
    }

    private ShoreCell rasterizeShore(int cx, int cz) {
        boolean[] wet = new boolean[9];
        int count = 0;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                boolean water = sample(cx + dx, cz + dz) != NODATA;
                wet[(dz + 1) * 3 + dx + 1] = water;
                if (water) count++;
            }
        }
        if (count == 0) return DRY_CELL;
        if (count == 9) return new ShoreCell(key(cx, cz), null);

        long[] owners = new long[cellBlocks * cellBlocks];
        for (int z = 0; z < cellBlocks; z++) {
            double gz = (z + 0.5) / cellBlocks - 0.5;
            int top = (int) Math.floor(gz);
            double tz = gz - top;
            for (int x = 0; x < cellBlocks; x++) {
                double gx = (x + 0.5) / cellBlocks - 0.5;
                int left = (int) Math.floor(gx);
                double tx = gx - left;
                double coverage = 0, strongest = -1;
                long owner = NO_OWNER;
                for (int dz = 0; dz <= 1; dz++) {
                    for (int dx = 0; dx <= 1; dx++) {
                        if (!wet[(top + dz + 1) * 3 + left + dx + 1]) continue;
                        double weight = (dx == 0 ? 1 - tx : tx) * (dz == 0 ? 1 - tz : tz);
                        coverage += weight;
                        if (weight > strongest) {
                            strongest = weight;
                            owner = key(cx + left + dx, cz + top + dz);
                        }
                    }
                }
                // Wet cells trim convex corners at 0.5; dry cells fill concave
                // corners at 0.375 (~0.47 for two orthogonal wet neighbours) while
                // still rejecting single-edge expansion (~0.33). Land borrows the
                // strongest wet neighbour's plane, never a land DEM sample.
                double keep = wet[4] ? 0.5 : 0.375;
                owners[z * cellBlocks + x] = coverage < keep ? NO_OWNER : wet[4] ? key(cx, cz) : owner;
            }
        }
        return new ShoreCell(NO_OWNER, owners);
    }

    private record ShoreCell(long uniformOwner, long[] owners) {
        long ownerAt(int index) {
            return owners == null ? uniformOwner : owners[index];
        }
    }

    // Serialize only new component planes; cached levels and coverage queries stay independent.
    private synchronized int resolvePlane(int cx, int cz) {
        long seed = key(cx, cz);
        Integer plane = planes.get(seed);
        if (plane != null) {
            return plane;
        }
        if (sample(cx, cz) == NODATA) {
            return NODATA;
        }
        var queue = new ArrayDeque<Long>();
        Set<Long> visited = new HashSet<>();
        var cells = new ArrayList<Long>();
        var heights = new ArrayList<Integer>();
        queue.add(seed);
        visited.add(seed);
        while (!queue.isEmpty()) {
            long cell = queue.removeFirst();
            int sx = (int) (cell >> 32), sz = (int) cell;
            int height = sample(sx, sz);
            if (height == NODATA) {
                continue;
            }
            cells.add(cell);
            heights.add(height);
            for (int[] offset : NEIGHBOURS) {
                long neighbour = key(sx + offset[0], sz + offset[1]);
                if (visited.add(neighbour)) {
                    queue.addLast(neighbour);
                }
            }
        }
        heights.sort(Integer::compareTo);
        // A lower decile ignores isolated DEM pits and high shoreline contamination.
        // Unlike a moving maximum, shore cliffs cannot lift a patch of lake water.
        int water = heights.get((heights.size() - 1) / 10);
        cells.forEach(cell -> planes.put(cell, water));
        return water;
    }

    private int sample(int cx, int cz) {
        if (cx < 0 || cz < 0 || cx >= width || cz >= depth) {
            return NODATA;
        }
        long cell = key(cx, cz);
        Integer cached = samples.get(cell);
        if (cached != null) {
            return cached;
        }
        if (negativeSamples.get(cell) != null) {
            return NODATA;
        }
        // No shared sampling lock: unrelated terrain workers can keep going.
        // Concurrent cold misses may duplicate work; neither source data nor levels mutate.
        int height = source.sample(originX + cx * cellBlocks + cellBlocks / 2,
            originZ + cz * cellBlocks + cellBlocks / 2);
        if (height != NODATA) {
            samples.putIfAbsent(cell, height);
        } else {
            negativeSamples.putIfAbsent(cell, true);
        }
        return height;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
}
