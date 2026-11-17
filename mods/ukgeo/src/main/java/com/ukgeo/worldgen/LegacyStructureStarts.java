package com.ukgeo.worldgen;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.neoforged.neoforge.event.level.ChunkEvent;

/** Removes empty markers saved by UKGeo's former water-overlap veto. */
final class LegacyStructureStarts {
    private LegacyStructureStarts() {}

    static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)
            || !(level.getChunkSource().getGenerator() instanceof UkGeoChunkGenerator)) {
            return;
        }
        // Do not load chunks here: the event can run before promotion to FULL.
        var chunk = event.getChunk();
        var starts = chunk.getAllStarts();
        var cleaned = withoutRejectedStarts(starts);
        if (cleaned != starts) {
            chunk.setAllStarts(cleaned);
            chunk.setUnsaved(true);
        }
    }

    static Map<Structure, StructureStart> withoutRejectedStarts(Map<Structure, StructureStart> starts) {
        if (!starts.containsValue(StructureStart.INVALID_START)) {
            return starts;
        }
        var cleaned = new HashMap<>(starts);
        // The serialized "INVALID" id also loads as this exact null-structure sentinel.
        cleaned.values().removeIf(start -> start == StructureStart.INVALID_START);
        return cleaned;
    }
}
