package com.ukgeo.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import org.junit.jupiter.api.Test;

class LegacyStructureStartsTest {
    @Test
    void removesSavedInvalidMarkerWithoutChangingInput() {
        var tag = new CompoundTag();
        tag.putString("id", "INVALID");
        var savedMarker = StructureStart.loadStaticStart(null, tag, 0L);
        assertSame(StructureStart.INVALID_START, savedMarker);
        Map<Structure, StructureStart> starts = Collections.singletonMap(null, savedMarker);

        var cleaned = LegacyStructureStarts.withoutRejectedStarts(starts);

        assertNotSame(starts, cleaned);
        assertTrue(cleaned.isEmpty());
        assertEquals(1, starts.size());
        assertSame(cleaned, LegacyStructureStarts.withoutRejectedStarts(cleaned));
    }

    @Test
    void retainsOtherStartsEvenWhenTheirPiecesAreEmpty() {
        var start = new StructureStart(null, new ChunkPos(-3, 7), 2, new PiecesContainer(List.of()));
        Map<Structure, StructureStart> starts = Collections.singletonMap(null, start);

        assertSame(starts, LegacyStructureStarts.withoutRejectedStarts(starts));
        assertSame(start, starts.get(null));
        assertEquals(2, start.getReferences());
    }

    @Test
    void leavesChunksWithoutStartsAlone() {
        Map<Structure, StructureStart> starts = Map.of();
        assertSame(starts, LegacyStructureStarts.withoutRejectedStarts(starts));
    }
}
