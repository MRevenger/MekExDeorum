package com.mekexdeorum;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parallelism granted by each Ex Deorum mesh, weakest first.
 *
 * <p>Pure Java: the machine looks a mesh up by the registry name of the item in its mesh slot, so
 * no Ex Deorum or Minecraft type is needed here and the table is unit-testable as a table.
 *
 * <p>The numbers are the ones from the specification (1 / 4 / 8 / 16 / 32 / 64). They differ from
 * the first implementation of the addon (1 / 2 / 4 / 16 / 32 / 64); the specification wins.
 */
public final class MeshTiers {

    /** Mesh registry names, weakest (string) to strongest (netherite). */
    public static final List<String> IDS = List.of(
            "exdeorum:string_mesh",
            "exdeorum:flint_mesh",
            "exdeorum:iron_mesh",
            "exdeorum:golden_mesh",
            "exdeorum:diamond_mesh",
            "exdeorum:netherite_mesh");

    /** Items sieved per operation by mesh registry name; iteration order follows {@link #IDS}. */
    public static final Map<String, Integer> PARALLELISM;

    static {
        Map<String, Integer> parallelism = new LinkedHashMap<>();
        parallelism.put("exdeorum:string_mesh", 1);
        parallelism.put("exdeorum:flint_mesh", 4);
        parallelism.put("exdeorum:iron_mesh", 8);
        parallelism.put("exdeorum:golden_mesh", 16);
        parallelism.put("exdeorum:diamond_mesh", 32);
        parallelism.put("exdeorum:netherite_mesh", 64);
        PARALLELISM = Collections.unmodifiableMap(parallelism);
    }

    private MeshTiers() {}

    /** Whether the given item registry name is one of Ex Deorum's meshes. */
    public static boolean isKnownMesh(String itemRegistryName) {
        return itemRegistryName != null && PARALLELISM.containsKey(itemRegistryName);
    }

    /** Items sieved per operation for that mesh, or 0 if the item is not a known mesh. */
    public static int parallelismFor(String itemRegistryName) {
        if (itemRegistryName == null) {
            return 0;
        }
        Integer parallelism = PARALLELISM.get(itemRegistryName);
        return parallelism == null ? 0 : parallelism;
    }
}
