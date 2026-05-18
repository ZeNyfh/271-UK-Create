package com.ukgeo.worldgen;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.io.IOException;
import java.nio.file.Path;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

public final class UkGeoVegetationBiomeSource extends BiomeSource {
    public static final MapCodec<UkGeoVegetationBiomeSource> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
        Biome.CODEC.fieldOf("fallback").forGetter(source -> source.fallback),
        Biome.CODEC.fieldOf("ocean").forGetter(source -> source.ocean),
        Biome.CODEC.fieldOf("river").forGetter(source -> source.river),
        Biome.CODEC.fieldOf("broadleaf_woodland").forGetter(source -> source.broadleafWoodland),
        Biome.CODEC.fieldOf("conifer_woodland").forGetter(source -> source.coniferWoodland),
        Biome.CODEC.fieldOf("arable").forGetter(source -> source.arable),
        Biome.CODEC.fieldOf("improved_grassland").forGetter(source -> source.improvedGrassland),
        Biome.CODEC.fieldOf("neutral_grassland").forGetter(source -> source.neutralGrassland),
        Biome.CODEC.fieldOf("calcareous_grassland").forGetter(source -> source.calcareousGrassland),
        Biome.CODEC.fieldOf("acid_grassland").forGetter(source -> source.acidGrassland),
        Biome.CODEC.fieldOf("wetland").forGetter(source -> source.wetland),
        Biome.CODEC.fieldOf("heath").forGetter(source -> source.heath),
        Biome.CODEC.fieldOf("freshwater").forGetter(source -> source.freshwater),
        Biome.CODEC.fieldOf("urban").forGetter(source -> source.urban),
        Biome.CODEC.fieldOf("rocky").forGetter(source -> source.rocky)
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
    private volatile RuntimeData runtimeData;
    private volatile boolean attemptedDataLoad;

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
        Holder<Biome> rocky
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
            rocky
        ).distinct();
    }

    @Override
    public Holder<Biome> getNoiseBiome(int quartX, int quartY, int quartZ, Climate.Sampler sampler) {
        int blockX = quartX << 2;
        int blockZ = quartZ << 2;
        RuntimeData data = data();
        if (data == null) {
            return fallback;
        }
        if (data.riverLayer != null && data.riverLayer.sample(blockX, blockZ).orElse(0) > 0) {
            return river;
        }
        int vegetationClass = data.vegetationLayer == null ? -1 : data.vegetationLayer.sample(blockX, blockZ).orElse(-1);
        return biomeForVegetationClass(vegetationClass);
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
                U8OreTileLayer vegetationLayer = manifest.vegetationPath == null
                    ? null
                    : new U8OreTileLayer(manifest, "vegetation", manifest.vegetationPath, manifest.vegetationCellBlocks, manifest.paddedWidth, manifest.paddedDepth);
                U8OreTileLayer riverLayer = manifest.riversPath == null ? null : new U8OreTileLayer(manifest, "rivers", manifest.riversPath);
                runtimeData = new RuntimeData(vegetationLayer, riverLayer);
            } catch (IOException | RuntimeException ex) {
                UkGeoMod.LOGGER.warn("UK vegetation biome data is missing or invalid; using fallback biome: {}", ex.getMessage());
                runtimeData = null;
            }
            return runtimeData;
        }
    }

    private record RuntimeData(U8OreTileLayer vegetationLayer, U8OreTileLayer riverLayer) {
    }
}
