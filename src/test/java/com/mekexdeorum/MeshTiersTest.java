package com.mekexdeorum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Tests for {@link MeshTiers}: the six mesh tiers of the specification and unknown items. */
class MeshTiersTest {

    @Test
    void theSixMeshesMapToTheSpecifiedParallelism() {
        assertEquals(1, MeshTiers.parallelismFor("exdeorum:string_mesh"));
        assertEquals(4, MeshTiers.parallelismFor("exdeorum:flint_mesh"));
        assertEquals(8, MeshTiers.parallelismFor("exdeorum:iron_mesh"));
        assertEquals(16, MeshTiers.parallelismFor("exdeorum:golden_mesh"));
        assertEquals(32, MeshTiers.parallelismFor("exdeorum:diamond_mesh"));
        assertEquals(64, MeshTiers.parallelismFor("exdeorum:netherite_mesh"));
    }

    @Test
    void everyListedMeshIsKnownAndMapped() {
        assertEquals(6, MeshTiers.IDS.size());
        assertEquals(6, MeshTiers.PARALLELISM.size());
        for (String id : MeshTiers.IDS) {
            assertTrue(MeshTiers.isKnownMesh(id), id + " should be a known mesh");
            assertTrue(MeshTiers.PARALLELISM.containsKey(id), id + " is missing from the table");
            assertTrue(MeshTiers.parallelismFor(id) > 0, id + " must grant parallelism");
        }
    }

    @Test
    void theTableIsOrderedWeakestToStrongest() {
        assertEquals(
                List.of(
                        "exdeorum:string_mesh",
                        "exdeorum:flint_mesh",
                        "exdeorum:iron_mesh",
                        "exdeorum:golden_mesh",
                        "exdeorum:diamond_mesh",
                        "exdeorum:netherite_mesh"),
                MeshTiers.IDS);

        int previous = 0;
        for (String id : MeshTiers.IDS) {
            int parallelism = MeshTiers.parallelismFor(id);
            assertTrue(parallelism > previous, id + " must be stronger than the previous tier");
            previous = parallelism;
        }
    }

    @Test
    void unknownOrMissingItemsGiveZero() {
        assertEquals(0, MeshTiers.parallelismFor("minecraft:diamond"));
        assertEquals(0, MeshTiers.parallelismFor("exdeorum:unknown_mesh"));
        assertEquals(0, MeshTiers.parallelismFor(""));
        assertEquals(0, MeshTiers.parallelismFor(null));
        assertFalse(MeshTiers.isKnownMesh("minecraft:diamond"));
        assertFalse(MeshTiers.isKnownMesh(null));
    }

    @Test
    void theParallelismTableCannotBeModified() {
        assertThrowsUnsupported(() -> MeshTiers.PARALLELISM.put("exdeorum:string_mesh", 99));
    }

    private static void assertThrowsUnsupported(Runnable action) {
        try {
            action.run();
        } catch (UnsupportedOperationException expected) {
            return;
        }
        throw new AssertionError("the parallelism table should be immutable");
    }
}
