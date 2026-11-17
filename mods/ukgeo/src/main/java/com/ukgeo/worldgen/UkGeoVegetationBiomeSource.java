package com.ukgeo.worldgen;

import com.ukgeo.worldgen.geo.UkGeoReference;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

public final class UkGeoVegetationBiomeSource extends BiomeSource {
    public static final MapCodec<UkGeoVegetationBiomeSource> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
        Biome.CODEC.fieldOf("fallback").forGetter((UkGeoVegetationBiomeSource source) -> source.fallback),
        Biome.CODEC.fieldOf("ocean").forGetter((UkGeoVegetationBiomeSource source) -> source.ocean),
        Biome.CODEC.fieldOf("river").forGetter((UkGeoVegetationBiomeSource source) -> source.river),
        Biome.CODEC.fieldOf("broadleaf_woodland").forGetter((UkGeoVegetationBiomeSource source) -> source.broadleafWoodland),
        Biome.CODEC.fieldOf("conifer_woodland").forGetter((UkGeoVegetationBiomeSource source) -> source.coniferWoodland),
        Biome.CODEC.fieldOf("arable").forGetter((UkGeoVegetationBiomeSource source) -> source.arable),
        Biome.CODEC.fieldOf("improved_grassland").forGetter((UkGeoVegetationBiomeSource source) -> source.improvedGrassland),
        Biome.CODEC.fieldOf("neutral_grassland").forGetter((UkGeoVegetationBiomeSource source) -> source.neutralGrassland),
        Biome.CODEC.fieldOf("calcareous_grassland").forGetter((UkGeoVegetationBiomeSource source) -> source.calcareousGrassland),
        Biome.CODEC.fieldOf("acid_grassland").forGetter((UkGeoVegetationBiomeSource source) -> source.acidGrassland),
        Biome.CODEC.fieldOf("wetland").forGetter((UkGeoVegetationBiomeSource source) -> source.wetland),
        Biome.CODEC.fieldOf("heath").forGetter((UkGeoVegetationBiomeSource source) -> source.heath),
        Biome.CODEC.fieldOf("freshwater").forGetter((UkGeoVegetationBiomeSource source) -> source.freshwater),
        Biome.CODEC.fieldOf("urban").forGetter((UkGeoVegetationBiomeSource source) -> source.urban),
        Biome.CODEC.fieldOf("rocky").forGetter((UkGeoVegetationBiomeSource source) -> source.rocky),
        ExtraBiomes.CODEC.codec().optionalFieldOf("extras").forGetter((UkGeoVegetationBiomeSource source) -> Optional.of(new ExtraBiomes(
            source.mountains, source.lushCaves, source.deepDark, source.dripstoneCaves,
            Optional.ofNullable(source.paleGarden), Optional.ofNullable(source.dappledForest), Optional.ofNullable(source.sulfurCaves))))
    ).apply(instance, UkGeoVegetationBiomeSource::new));

    private final Holder<Biome> fallback;
    private final Holder<Biome> ocean;
    private final Holder<Biome> river;
    private final Holder<Biome> broadleafWoodland;
    private final Holder<Biome> coniferWoodland;
    private final Holder<Biome> arable;
    private final Holder<Biome> improvedGrassland;
    private final Holder<Biome> neutralGrassland;
    private final Holder<Biome> calcareousGrassland;
    private final Holder<Biome> acidGrassland;
    private final Holder<Biome> wetland;
    private final Holder<Biome> heath;
    private final Holder<Biome> freshwater;
    private final Holder<Biome> urban;
    private final Holder<Biome> rocky;
    private final Holder<Biome> mountains;
    private final Holder<Biome> lushCaves;
    private final Holder<Biome> deepDark;
    private final Holder<Biome> dripstoneCaves;
    private final Holder<Biome> paleGarden;
    private final Holder<Biome> dappledForest;
    private final Holder<Biome> sulfurCaves;
    private volatile RuntimeData runtimeData;
    private volatile boolean attemptedDataLoad;

    // Hot-path caches: System property / Integer.getInteger / Double.parseDouble once at class init.
    private static final int UNDERGROUND_BIOME_MAX_Y = Integer.getInteger("ukgeo.undergroundBiomeMaxY", 48);
    private static final int DEEP_DARK_MAX_Y = Integer.getInteger("ukgeo.deepDarkMaxY", 8);
    private static final int LUSH_CAVES_MAX_Y = Integer.getInteger("ukgeo.lushCavesMaxY", 40);
    private static final int DRIPSTONE_CAVES_MAX_Y = Integer.getInteger("ukgeo.dripstoneCavesMaxY", 24);
    private static final int SULFUR_CAVES_MAX_Y = Integer.getInteger("ukgeo.sulfurCavesMaxY", 40);
    private static final double LUSH_CAVES_NOISE_THRESHOLD =
        Double.parseDouble(System.getProperty("ukgeo.lushCavesNoiseThreshold", "0.68"));
    private static final double DRIPSTONE_CAVES_NOISE_THRESHOLD =
        Double.parseDouble(System.getProperty("ukgeo.dripstoneCavesNoiseThreshold", "-0.52"));
    private static final Double SULFUR_NOISE_OVERRIDE = optionalDoubleProperty("ukgeo.sulfurCavesNoiseThreshold");
    private static final int MOUNTAIN_BIOME_SOURCE_HEIGHT_DECIMETRES =
        Integer.getInteger("ukgeo.mountainBiomeSourceHeightDecimetres", 4_200);

    private static Double optionalDoubleProperty(String key) {
        String raw = System.getProperty(key);
        return raw == null ? null : Double.parseDouble(raw);
    }

    public UkGeoVegetationBiomeSource(
        Holder<Biome> fallback,
        Holder<Biome> ocean,
        Holder<Biome> river,
        Holder<Biome> broadleafWoodland,
        Holder<Biome> coniferWoodland,
        Holder<Biome> arable,
        Holder<Biome> improvedGrassland,
        Holder<Biome> neutralGrassland,
        Holder<Biome> calcareousGrassland,
        Holder<Biome> acidGrassland,
        Holder<Biome> wetland,
        Holder<Biome> heath,
        Holder<Biome> freshwater,
        Holder<Biome> urban,
        Holder<Biome> rocky,
        Optional<ExtraBiomes> extras
    ) {
        this.fallback = fallback;
        this.ocean = ocean;
        this.river = river;
        this.broadleafWoodland = broadleafWoodland;
        this.coniferWoodland = coniferWoodland;
        this.arable = arable;
        this.improvedGrassland = improvedGrassland;
        this.neutralGrassland = neutralGrassland;
        this.calcareousGrassland = calcareousGrassland;
        this.acidGrassland = acidGrassland;
        this.wetland = wetland;
        this.heath = heath;
        this.freshwater = freshwater;
        this.urban = urban;
        this.rocky = rocky;
        ExtraBiomes resolved = extras.orElse(null);
        this.mountains = resolved == null ? rocky : resolved.mountains();
        this.lushCaves = resolved == null ? fallback : resolved.lushCaves();
        this.deepDark = resolved == null ? this.mountains : resolved.deepDark();
        this.dripstoneCaves = resolved == null ? rocky : resolved.dripstoneCaves();
        this.paleGarden = resolved == null ? null : resolved.paleGarden().orElse(null);
        this.dappledForest = resolved == null ? null : resolved.dappledForest().orElse(null);
        this.sulfurCaves = resolved == null ? null : resolved.sulfurCaves().orElse(null);
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return CODEC;
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        return Stream.of(
            fallback,
            ocean,
            river,
            broadleafWoodland,
            coniferWoodland,
            arable,
            improvedGrassland,
            neutralGrassland,
            calcareousGrassland,
            acidGrassland,
            wetland,
            heath,
            freshwater,
            urban,
            rocky,
            mountains,
            lushCaves,
            deepDark,
            dripstoneCaves,
            paleGarden,
            dappledForest,
            sulfurCaves
        ).filter(holder -> holder != null).distinct();
    }

    @Override
    public Holder<Biome> getNoiseBiome(int quartX, int quartY, int quartZ, Climate.Sampler sampler) {
        int blockX = quartX << 2;
        int blockZ = quartZ << 2;
        RuntimeData data = data();
        if (data == null) {
            return fallback;
        }
        int sourceHeightDecimetres = data.height.sampleDecimetresOrNodata(blockX, blockZ);
        if (sourceHeightDecimetres == R16HeightTileLayer.NODATA) {
            // The UKGeo height layer uses no-data/negative height for the surrounding sea.
            // Treat that area as ocean for structure/biome purposes so ocean structures can
            // select those chunks instead of falling back to a land biome.
            return ocean;
        }
        double easting = Double.NaN;
        double northing = Double.NaN;
        boolean haveBng = false;
        if (data.reference != null) {
            var bng = data.reference.minecraftToBng(blockX, blockZ);
            if (bng.isPresent()) {
                easting = bng.get().easting();
                northing = bng.get().northing();
                haveBng = true;
            }
        }
        Holder<Biome> underground = undergroundBiomeFor(
            blockYFromQuart(quartY), sourceHeightDecimetres, blockX, blockZ, haveBng, easting, northing);
        if (underground != null) {
            return underground;
        }
        if (data.riverLayer != null && data.riverLayer.sampleOrDefault(blockX, blockZ, 0) > 0) {
            return river;
        }
        if (isHighMountainSourceHeight(sourceHeightDecimetres)) {
            return mountains;
        }
        int biomeClass = smoothedBiomeClass(data, blockX, blockZ);
        if (haveBng && VanillaBackportBiomeRegions.shouldExpandForest(easting, northing, biomeClass, 0L)) {
            biomeClass = VanillaBackportBiomeRegions.expandedWoodlandClass(easting, northing);
        }
        if (haveBng && (biomeClass == 1 || biomeClass == 2)) {
            Holder<Biome> remapped = vanillaBackportWoodland(easting, northing);
            if (remapped != null) {
                return remapped;
            }
        }
        return biomeForVegetationClass(biomeClass);
    }

    private Holder<Biome> vanillaBackportWoodland(double easting, double northing) {
        return switch (VanillaBackportBiomeRegions.classify(easting, northing)) {
            case PALE_GARDEN -> paleGarden;
            case DAPPLED_FOREST -> dappledForest;
            case NONE -> null;
        };
    }

    private int smoothedBiomeClass(RuntimeData data, int blockX, int blockZ) {
        if (data.biomeRegionLayer == null) {
            return data.vegetationLayer == null ? -1 : data.vegetationLayer.sampleOrDefault(blockX, blockZ, -1);
        }
        int center = data.biomeRegionLayer.sampleOrDefault(blockX, blockZ, -1);
        if (center < 0 || center == 0 || center == 10) {
            return center;
        }
        int step = Math.max(4, Math.max(1, data.biomeRegionCellBlocks) / 2);
        int[] samples = {
            center,
            data.biomeRegionLayer.sampleOrDefault(blockX - step, blockZ, center),
            data.biomeRegionLayer.sampleOrDefault(blockX + step, blockZ, center),
            data.biomeRegionLayer.sampleOrDefault(blockX, blockZ - step, center),
            data.biomeRegionLayer.sampleOrDefault(blockX, blockZ + step, center),
        };
        int[] weights = {4, 2, 2, 2, 2};
        int[] counts = new int[256];
        for (int i = 0; i < samples.length; i++) {
            int sample = samples[i];
            if (sample < 0 || sample >= counts.length) {
                continue;
            }
            counts[sample] += weights[i];
        }
        int best = center;
        int bestWeight = counts[center];
        for (int classId = 0; classId < counts.length; classId++) {
            int weight = counts[classId];
            if (weight > bestWeight) {
                best = classId;
                bestWeight = weight;
            }
        }
        return best;
    }

    private Holder<Biome> undergroundBiomeFor(
        int blockY,
        int sourceHeightDecimetres,
        int blockX,
        int blockZ,
        boolean haveBng,
        double easting,
        double northing
    ) {
        if (blockY >= UNDERGROUND_BIOME_MAX_Y) {
            return null;
        }
        double caveNoise = valueNoise(blockX, blockZ, 0.0045, 0x4341564542494f4dL);
        if (isHighMountainSourceHeight(sourceHeightDecimetres) && blockY <= DEEP_DARK_MAX_Y) {
            return deepDark;
        }
        // Sulfur before lush: Wales AABB + noise; Vale core easier than soft Wales.
        // Defaults: Vale 0.20, soft Wales 0.40 (override both via -Dukgeo.sulfurCavesNoiseThreshold).
        if (sulfurCaves != null && blockY <= SULFUR_CAVES_MAX_Y && haveBng
            && VanillaBackportBiomeRegions.inSulfurWales(easting, northing)) {
            double threshold = SULFUR_NOISE_OVERRIDE != null
                ? SULFUR_NOISE_OVERRIDE
                : VanillaBackportBiomeRegions.defaultSulfurNoiseThreshold(easting, northing);
            if (caveNoise > threshold) {
                return sulfurCaves;
            }
        }
        if (blockY <= LUSH_CAVES_MAX_Y && caveNoise > LUSH_CAVES_NOISE_THRESHOLD) {
            return lushCaves;
        }
        if (blockY <= DRIPSTONE_CAVES_MAX_Y && caveNoise < DRIPSTONE_CAVES_NOISE_THRESHOLD) {
            return dripstoneCaves;
        }
        return null;
    }

    private static int blockYFromQuart(int quartY) {
        return quartY << 2;
    }

    private static double valueNoise(int x, int z, double frequency, long salt) {
        int ix = (int) Math.floor(x * frequency);
        int iz = (int) Math.floor(z * frequency);
        double fx = x * frequency - ix;
        double fz = z * frequency - iz;
        double a = hashSigned(ix, iz, salt);
        double b = hashSigned(ix + 1, iz, salt);
        double c = hashSigned(ix, iz + 1, salt);
        double d = hashSigned(ix + 1, iz + 1, salt);
        double sx = smoothstep(fx);
        double sz = smoothstep(fz);
        return lerp(lerp(a, b, sx), lerp(c, d, sx), sz);
    }

    private static double hashSigned(int x, int z, long salt) {
        long value = x * 0x9E3779B97F4A7C15L ^ z * 0xC2B2AE3D27D4EB4FL ^ salt;
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        value ^= value >>> 31;
        return ((value >>> 11) * 0x1.0p-53) * 2.0 - 1.0;
    }

    private static double smoothstep(double value) {
        double t = Math.clamp(value, 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static boolean isHighMountainSourceHeight(int decimetres) {
        /*
         * BiomeSource does not receive the chunk generator's final shaped surface Y, but in
         * the current UKGeo preset a source height around 420m maps to roughly Y=140+ after
         * sea level and height scaling. Make this threshold tunable for future presets.
         */
        return decimetres >= MOUNTAIN_BIOME_SOURCE_HEIGHT_DECIMETRES;
    }

    private Holder<Biome> biomeForVegetationClass(int vegetationClass) {
        return switch (vegetationClass) {
            case 0 -> ocean;
            case 1 -> broadleafWoodland;
            case 2 -> coniferWoodland;
            case 3 -> arable;
            case 4 -> improvedGrassland;
            case 5 -> neutralGrassland;
            case 6 -> calcareousGrassland;
            case 7 -> acidGrassland;
            case 8 -> wetland;
            case 9 -> heath;
            case 10 -> freshwater;
            case 11 -> urban;
            case 12 -> rocky;
            default -> fallback;
        };
    }

    private RuntimeData data() {
        RuntimeData data = runtimeData;
        if (data != null || attemptedDataLoad) {
            return data;
        }
        synchronized (this) {
            if (runtimeData != null || attemptedDataLoad) {
                return runtimeData;
            }
            attemptedDataLoad = true;
            Path root = UkGeoConfig.dataRoot(Path.of(".").toAbsolutePath().normalize());
            try {
                TileManifest manifest = TileManifest.load(root);
                R16HeightTileLayer height = new R16HeightTileLayer(manifest);
                U8OreTileLayer vegetationLayer = manifest.vegetationPath == null
                    ? null
                    : new U8OreTileLayer(manifest, "vegetation", manifest.vegetationPath, manifest.vegetationCellBlocks, manifest.paddedWidth, manifest.paddedDepth);
                U8OreTileLayer biomeRegionLayer = manifest.biomeRegionsPath == null
                    ? null
                    : new U8OreTileLayer(manifest, "biome_regions", manifest.biomeRegionsPath, manifest.biomeRegionsCellBlocks, manifest.paddedWidth, manifest.paddedDepth);
                U8OreTileLayer riverLayer = manifest.riversPath == null ? null : new U8OreTileLayer(manifest, "rivers", manifest.riversPath);
                runtimeData = new RuntimeData(height, vegetationLayer, biomeRegionLayer, riverLayer, manifest.biomeRegionsCellBlocks, manifest.toReference());
            } catch (IOException | RuntimeException ex) {
                UkGeoMod.LOGGER.warn("UK vegetation biome data is missing or invalid; using fallback biome: {}", ex.getMessage());
                runtimeData = null;
            }
            return runtimeData;
        }
    }

    private record ExtraBiomes(
        Holder<Biome> mountains,
        Holder<Biome> lushCaves,
        Holder<Biome> deepDark,
        Holder<Biome> dripstoneCaves,
        Optional<Holder<Biome>> paleGarden,
        Optional<Holder<Biome>> dappledForest,
        Optional<Holder<Biome>> sulfurCaves
    ) {
        private static final MapCodec<ExtraBiomes> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Biome.CODEC.fieldOf("mountains").forGetter(ExtraBiomes::mountains),
            Biome.CODEC.fieldOf("lush_caves").forGetter(ExtraBiomes::lushCaves),
            Biome.CODEC.fieldOf("deep_dark").forGetter(ExtraBiomes::deepDark),
            Biome.CODEC.fieldOf("dripstone_caves").forGetter(ExtraBiomes::dripstoneCaves),
            Biome.CODEC.optionalFieldOf("pale_garden").forGetter(ExtraBiomes::paleGarden),
            Biome.CODEC.optionalFieldOf("dappled_forest").forGetter(ExtraBiomes::dappledForest),
            Biome.CODEC.optionalFieldOf("sulfur_caves").forGetter(ExtraBiomes::sulfurCaves)
        ).apply(instance, ExtraBiomes::new));
    }

    private record RuntimeData(
        R16HeightTileLayer height,
        U8OreTileLayer vegetationLayer,
        U8OreTileLayer biomeRegionLayer,
        U8OreTileLayer riverLayer,
        int biomeRegionCellBlocks,
        UkGeoReference reference
    ) {
    }
}
