package com.ukgeo.worldgen.dh;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.EDhApiDetailLevel;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGeneratorReturnType;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBiomeWrapper;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBlockStateWrapper;
import com.seibel.distanthorizons.api.interfaces.override.worldGenerator.IDhApiWorldGenerator;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.objects.data.DhApiChunk;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import com.ukgeo.worldgen.UkGeoChunkGenerator;
import com.ukgeo.worldgen.UkGeoMod;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * Generates Distant Horizons {@link EDhApiWorldGeneratorReturnType#API_CHUNKS} from UK height/surface tiles.
 * Never uses {@link EDhApiDistantGeneratorMode#INTERNAL_SERVER} / vanilla {@code fillFromNoise}.
 */
public final class UkGeoDhLodWorldGenerator implements IDhApiWorldGenerator {
    private static final byte BLOCK_DETAIL = EDhApiDetailLevel.BLOCK.detailLevel;
    private static final int DIRT_DEPTH = 3;
    private static final int SURFACE_ROCK_DEPTH = 12;
    private static final String BIOME_COASTAL_OCEAN = "ukgeo:coastal_ocean";
    private static final String BIOME_FRESHWATER = "ukgeo:freshwater";
    private static final String BIOME_FALLBACK = "ukgeo:improved_grassland";

    private final IDhApiLevelWrapper levelWrapper;
    private final UkGeoChunkGenerator generator;
    private final ConcurrentHashMap<BlockState, IDhApiBlockStateWrapper> blockWrappers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, IDhApiBiomeWrapper> biomeWrappers = new ConcurrentHashMap<>();
    private volatile IDhApiBlockStateWrapper airWrapper;
    private volatile IDhApiBlockStateWrapper stoneWrapper;
    private volatile IDhApiBlockStateWrapper deepslateWrapper;
    private volatile IDhApiBlockStateWrapper dirtWrapper;
    private volatile IDhApiBlockStateWrapper grassWrapper;
    private volatile IDhApiBlockStateWrapper sandWrapper;
    private volatile IDhApiBlockStateWrapper waterWrapper;
    private volatile IDhApiBlockStateWrapper podzolWrapper;
    private volatile IDhApiBlockStateWrapper coarseDirtWrapper;
    private volatile IDhApiBlockStateWrapper mossWrapper;
    private volatile IDhApiBlockStateWrapper oakLeavesWrapper;
    private volatile IDhApiBlockStateWrapper spruceLeavesWrapper;
    private volatile boolean wrappersReady;

    UkGeoDhLodWorldGenerator(IDhApiLevelWrapper levelWrapper, UkGeoChunkGenerator generator) {
        this.levelWrapper = levelWrapper;
        this.generator = generator;
    }

    @Override
    public EDhApiWorldGeneratorReturnType getReturnType() {
        return EDhApiWorldGeneratorReturnType.API_CHUNKS;
    }

    @Override
    public boolean runApiValidation() {
        return false;
    }

    @Override
    public CompletableFuture<Void> generateApiChunks(
        int chunkPosMinX,
        int chunkPosMinZ,
        int generationRequestChunkWidthCount,
        byte targetDataDetail,
        EDhApiDistantGeneratorMode generatorMode,
        ExecutorService worldGeneratorThreadPool,
        Consumer<DhApiChunk> resultConsumer
    ) {
        // Always sample UK tiles directly — never ask MC to INTERNAL_SERVER / fillFromNoise.
        return CompletableFuture.runAsync(
            () -> {
                ensureWrappers();
                for (int chunkX = chunkPosMinX; chunkX < chunkPosMinX + generationRequestChunkWidthCount; chunkX++) {
                    for (int chunkZ = chunkPosMinZ; chunkZ < chunkPosMinZ + generationRequestChunkWidthCount; chunkZ++) {
                        resultConsumer.accept(buildApiChunk(chunkX, chunkZ));
                    }
                }
            },
            worldGeneratorThreadPool
        );
    }

    private DhApiChunk buildApiChunk(int chunkPosX, int chunkPosZ) {
        UkGeoChunkGenerator.LodColumnSample probe = generator.sampleLodColumn(chunkPosX << 4, chunkPosZ << 4);
        int minY = probe.minY();
        int maxY = probe.maxY();
        DhApiChunk apiChunk = DhApiChunk.create(chunkPosX, chunkPosZ, minY, maxY);
        int originX = chunkPosX << 4;
        int originZ = chunkPosZ << 4;
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                UkGeoChunkGenerator.LodColumnSample sample =
                    generator.sampleLodColumn(originX + localX, originZ + localZ);
                apiChunk.setDataPoints(localX, localZ, buildColumn(sample));
            }
        }
        return apiChunk;
    }

    private List<DhApiTerrainDataPoint> buildColumn(UkGeoChunkGenerator.LodColumnSample sample) {
        int minY = sample.minY();
        int maxY = Math.max(sample.maxY(), minY + 1);
        int surface = Math.clamp(sample.surfaceY(), minY, maxY - 1);
        int sea = sample.seaLevelY();
        boolean oceanOrFlooded = !sample.hasHeightData() || surface < sea;
        IDhApiBlockStateWrapper surfaceRock = wrap(sample.surfaceRock());
        IDhApiBiomeWrapper biome = resolveBiome(sample, oceanOrFlooded);

        ArrayList<Segment> segments = new ArrayList<>(10);
        if (oceanOrFlooded) {
            int solidTop = surface + 1;
            int geologyBottom = Math.max(minY, solidTop - SURFACE_ROCK_DEPTH);
            appendDeepslateThenStone(segments, minY, geologyBottom);
            if (geologyBottom < solidTop) {
                segments.add(new Segment(geologyBottom, solidTop, surfaceRock));
            }
            if (surface + 1 <= sea) {
                segments.add(new Segment(surface + 1, sea + 1, waterWrapper));
            }
            int airBottom = Math.max(surface + 1, sea + 1);
            if (airBottom < maxY) {
                segments.add(new Segment(airBottom, maxY, airWrapper));
            }
        } else if (sample.riverWater()) {
            int bed = Math.clamp(sample.terrainFloorY(), minY + 1, maxY - 1);
            int waterSurface = Math.clamp(sample.waterSurfaceY(), bed, maxY - 1);
            IDhApiBlockStateWrapper floor = wrap(sample.floorMaterial());
            int geologyBottom = Math.max(minY, bed - SURFACE_ROCK_DEPTH + 1);
            appendDeepslateThenStone(segments, minY, geologyBottom);
            if (geologyBottom < bed) {
                segments.add(new Segment(geologyBottom, bed, surfaceRock));
            }
            segments.add(new Segment(bed, bed + 1, floor));
            if (bed + 1 <= waterSurface) {
                segments.add(new Segment(bed + 1, waterSurface + 1, waterWrapper));
            }
            int airBottom = waterSurface + 1;
            if (airBottom < maxY) {
                segments.add(new Segment(airBottom, maxY, airWrapper));
            }
        } else if (sample.steep()) {
            // Match real gen: no dirt/grass — geology up to surface, exposed rock on top.
            int geologyBottom = Math.max(minY, surface - SURFACE_ROCK_DEPTH + 1);
            appendDeepslateThenStone(segments, minY, geologyBottom);
            if (geologyBottom < surface) {
                segments.add(new Segment(geologyBottom, surface, surfaceRock));
            }
            segments.add(new Segment(surface, surface + 1, landTopBlock(sample, surfaceRock)));
            int aboveSurface = surface + 1;
            if (aboveSurface < maxY) {
                segments.add(new Segment(aboveSurface, maxY, airWrapper));
            }
        } else {
            int dirtBottom = Math.max(minY, surface - DIRT_DEPTH + 1);
            int geologyBottom = Math.max(minY, surface - SURFACE_ROCK_DEPTH + 1);
            appendDeepslateThenStone(segments, minY, geologyBottom);
            if (geologyBottom < dirtBottom) {
                segments.add(new Segment(geologyBottom, dirtBottom, surfaceRock));
            }
            if (dirtBottom < surface) {
                segments.add(new Segment(dirtBottom, surface, dirtWrapper));
            }
            segments.add(new Segment(surface, surface + 1, landTopBlock(sample, surfaceRock)));
            int canopyTop = sample.canopyTopY();
            int aboveSurface = surface + 1;
            if (canopyTop > aboveSurface) {
                int leafTop = Math.min(canopyTop, maxY);
                if (aboveSurface < leafTop) {
                    segments.add(new Segment(aboveSurface, leafTop, canopyLeaves(sample.vegetationClass())));
                }
                if (leafTop < maxY) {
                    segments.add(new Segment(leafTop, maxY, airWrapper));
                }
            } else if (aboveSurface < maxY) {
                segments.add(new Segment(aboveSurface, maxY, airWrapper));
            }
        }

        ArrayList<DhApiTerrainDataPoint> points = new ArrayList<>(segments.size());
        for (Segment segment : segments) {
            if (segment.bottomY >= segment.topY) {
                continue;
            }
            // Sky lighting left at 0; DH auto-lights submitted LODs.
            points.add(DhApiTerrainDataPoint.create(
                BLOCK_DETAIL,
                0,
                0,
                segment.bottomY,
                segment.topY,
                segment.block,
                biome
            ));
        }
        if (points.isEmpty()) {
            points.add(DhApiTerrainDataPoint.create(BLOCK_DETAIL, 0, 0, minY, maxY, airWrapper, biome));
        }
        return points;
    }

    private IDhApiBiomeWrapper resolveBiome(UkGeoChunkGenerator.LodColumnSample sample, boolean oceanOrFlooded) {
        return biomeWrapper(lodBiomeId(sample, oceanOrFlooded));
    }

    private static String lodBiomeId(UkGeoChunkGenerator.LodColumnSample sample, boolean oceanOrFlooded) {
        if (oceanOrFlooded) {
            return BIOME_COASTAL_OCEAN;
        }
        if (sample.riverWater()) {
            return BIOME_FRESHWATER;
        }
        return switch (sample.vegetationClass()) {
            case UkGeoChunkGenerator.VEGETATION_BROADLEAF_WOODLAND -> "ukgeo:broadleaf_woodland";
            case UkGeoChunkGenerator.VEGETATION_CONIFER_WOODLAND -> "ukgeo:conifer_woodland";
            case UkGeoChunkGenerator.VEGETATION_ARABLE -> "ukgeo:arable";
            case UkGeoChunkGenerator.VEGETATION_IMPROVED_GRASSLAND -> "ukgeo:improved_grassland";
            case UkGeoChunkGenerator.VEGETATION_NEUTRAL_GRASSLAND -> "ukgeo:neutral_grassland";
            case UkGeoChunkGenerator.VEGETATION_CALCAREOUS_GRASSLAND -> "ukgeo:calcareous_grassland";
            case UkGeoChunkGenerator.VEGETATION_ACID_GRASSLAND -> "ukgeo:acid_grassland";
            case UkGeoChunkGenerator.VEGETATION_WETLAND -> "ukgeo:wetland";
            case UkGeoChunkGenerator.VEGETATION_HEATH -> "ukgeo:heath";
            case UkGeoChunkGenerator.VEGETATION_FRESHWATER -> BIOME_FRESHWATER;
            case UkGeoChunkGenerator.VEGETATION_URBAN -> "ukgeo:urban";
            case UkGeoChunkGenerator.VEGETATION_ROCKY -> "ukgeo:rocky";
            default -> BIOME_FALLBACK;
        };
    }

    private IDhApiBlockStateWrapper landTopBlock(
        UkGeoChunkGenerator.LodColumnSample sample,
        IDhApiBlockStateWrapper surfaceRock
    ) {
        if (sample.coastalBeach()) {
            return sandWrapper;
        }
        if (sample.steep()) {
            return wrap(sample.exposedSurfaceRock());
        }
        return switch (sample.vegetationClass()) {
            case UkGeoChunkGenerator.VEGETATION_ROCKY -> surfaceRock;
            case UkGeoChunkGenerator.VEGETATION_ARABLE -> dirtWrapper;
            case UkGeoChunkGenerator.VEGETATION_HEATH -> podzolWrapper;
            case UkGeoChunkGenerator.VEGETATION_ACID_GRASSLAND -> coarseDirtWrapper;
            case UkGeoChunkGenerator.VEGETATION_WETLAND -> mossWrapper;
            default -> grassWrapper;
        };
    }

    private IDhApiBlockStateWrapper canopyLeaves(int vegetationClass) {
        if (vegetationClass == UkGeoChunkGenerator.VEGETATION_CONIFER_WOODLAND) {
            return spruceLeavesWrapper;
        }
        return oakLeavesWrapper;
    }

    private void appendDeepslateThenStone(ArrayList<Segment> segments, int fromY, int toY) {
        if (fromY >= toY) {
            return;
        }
        int deepslateTop = Math.min(toY, Math.max(fromY, 0));
        if (fromY < deepslateTop) {
            segments.add(new Segment(fromY, deepslateTop, deepslateWrapper));
        }
        if (deepslateTop < toY) {
            segments.add(new Segment(deepslateTop, toY, stoneWrapper));
        }
    }

    private void ensureWrappers() {
        if (wrappersReady) {
            return;
        }
        synchronized (this) {
            if (wrappersReady) {
                return;
            }
            try {
                airWrapper = DhApi.Delayed.wrapperFactory.getAirBlockStateWrapper();
                stoneWrapper = wrap(Blocks.STONE.defaultBlockState());
                deepslateWrapper = wrap(Blocks.DEEPSLATE.defaultBlockState());
                dirtWrapper = wrap(Blocks.DIRT.defaultBlockState());
                grassWrapper = wrap(Blocks.GRASS_BLOCK.defaultBlockState());
                sandWrapper = wrap(Blocks.SAND.defaultBlockState());
                waterWrapper = wrap(Blocks.WATER.defaultBlockState());
                podzolWrapper = wrap(Blocks.PODZOL.defaultBlockState());
                coarseDirtWrapper = wrap(Blocks.COARSE_DIRT.defaultBlockState());
                mossWrapper = wrap(Blocks.MOSS_BLOCK.defaultBlockState());
                oakLeavesWrapper = wrap(Blocks.OAK_LEAVES.defaultBlockState());
                spruceLeavesWrapper = wrap(Blocks.SPRUCE_LEAVES.defaultBlockState());
                // Warm biome cache with fallback so first columns don't race cold-init.
                biomeWrapper(BIOME_FALLBACK);
                wrappersReady = true;
            } catch (RuntimeException ex) {
                UkGeoMod.LOGGER.warn("UKGeo DH LOD wrapper init failed: {}", ex.toString());
                throw new IllegalStateException("UKGeo DH LOD wrappers unavailable", ex);
            }
        }
    }

    private IDhApiBiomeWrapper biomeWrapper(String biomeId) {
        return biomeWrappers.computeIfAbsent(biomeId, key -> {
            try {
                return DhApi.Delayed.wrapperFactory.getBiomeWrapper(key, levelWrapper);
            } catch (IOException | RuntimeException ex) {
                UkGeoMod.LOGGER.warn("UKGeo DH LOD biome wrapper failed for {}: {}", key, ex.toString());
                try {
                    return DhApi.Delayed.wrapperFactory.getBiomeWrapper(BIOME_FALLBACK, levelWrapper);
                } catch (IOException | RuntimeException fallbackEx) {
                    throw new IllegalStateException("UKGeo DH LOD biome wrappers unavailable", fallbackEx);
                }
            }
        });
    }

    private IDhApiBlockStateWrapper wrap(BlockState state) {
        return blockWrappers.computeIfAbsent(state, key ->
            DhApi.Delayed.wrapperFactory.getBlockStateWrapper(new Object[]{key}, levelWrapper)
        );
    }

    @Override
    public void preGeneratorTaskStart() {
        // no-op
    }

    @Override
    public void close() {
        blockWrappers.clear();
        biomeWrappers.clear();
    }

    private record Segment(int bottomY, int topY, IDhApiBlockStateWrapper block) {
    }
}
