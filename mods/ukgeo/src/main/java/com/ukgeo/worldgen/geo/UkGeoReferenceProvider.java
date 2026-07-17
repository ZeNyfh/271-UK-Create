package com.ukgeo.worldgen.geo;

import com.ukgeo.worldgen.UkGeoChunkGenerator;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;

public final class UkGeoReferenceProvider {
    private UkGeoReferenceProvider() {
    }

    public static Optional<UkGeoReference> get(ServerLevel level) {
        if (level == null) {
            return Optional.empty();
        }
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        if (generator instanceof UkGeoChunkGenerator ukGeoChunkGenerator) {
            return ukGeoChunkGenerator.reference();
        }
        return Optional.empty();
    }
}
