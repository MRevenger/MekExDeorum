package com.mekexdeorum.compat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.providers.number.BinomialDistributionGenerator;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;
import net.minecraft.world.level.storage.loot.providers.number.NumberProvider;
import net.minecraft.world.level.storage.loot.providers.number.UniformGenerator;
import thedarkcolour.exdeorum.loot.SummationGenerator;
import thedarkcolour.exdeorum.recipe.sieve.SieveRecipe;
import thedarkcolour.exdeorum.registry.ERecipeTypes;
import thedarkcolour.exdeorum.tag.EItemTags;

/**
 * Everything that touches Ex Deorum lives here, and nothing else in this mod does.
 *
 * <p>Recipes are read from the vanilla {@link RecipeManager} through Ex Deorum's {@code
 * exdeorum:sieve} recipe type, never through {@code RecipeUtil} / {@code RecipeCaches}: those two
 * changed shape between releases (3.x has static {@code RecipeUtil.getSieveRecipes(...)}, 3.12 has
 * {@code RecipeUtil.getCaches(level)...}) and referencing either one throws {@link NoSuchMethodError}
 * on the other version. Reading the recipe type directly also picks up recipes added or removed by a
 * datapack or a script, which Ex Deorum's own cache does not.
 *
 * <p>The cache is keyed on the identity of the {@link RecipeManager}: a datapack reload builds a
 * fresh {@code ReloadableServerResources} ({@code MinecraftServer#reloadResources}) and therefore a
 * fresh recipe manager, so the cache cannot outlive the data it was built from.
 */
public final class ExDeorumCompat {

    /** One sifting result, decomposed into a per-event drop chance and a per-success amount. */
    public record Output(Item item, double chance, long count) {}

    /**
     * Cache of every sifting recipe, indexed by mesh item and then by input item.
     *
     * <p>Built from the recipe's own ingredient and mesh lists so the two lookups that happen on
     * every machine tick stay O(1); {@link #recipesFor} still confirms the hit with the recipe's
     * {@link SieveRecipe#matches}, which is the authoritative test for the input item.
     */
    private static RecipeManager cachedManager;

    private static Map<Item, Map<Item, List<SieveRecipe>>> byMeshAndInput = Map.of();

    private ExDeorumCompat() {}

    /** True for anything in Ex Deorum's {@code exdeorum:sieve_meshes} tag, i.e. a usable mesh. */
    public static boolean isMesh(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.is(EItemTags.SIEVE_MESHES);
    }

    /** Whether any sifting recipe accepts that input with that mesh. */
    public static boolean canSift(ServerLevel level, ItemStack mesh, ItemStack input) {
        return !recipesFor(level, mesh, input).isEmpty();
    }

    /**
     * The recipe outputs for one input item and one mesh, already decomposed into chance and amount.
     *
     * <p>Called once per finished operation, never per consumed item. Recipes flagged
     * {@code by_hand_only} are pruned from the cache entirely: Ex Deorum's own mechanical sieve
     * produces nothing for them, so a machine that cannot use them must not consume the input.
     */
    public static List<Output> outputs(ServerLevel level, ItemStack mesh, ItemStack input) {
        List<SieveRecipe> recipes = recipesFor(level, mesh, input);
        if (recipes.isEmpty()) {
            return List.of();
        }
        LootContext context = emptyLootContext(level);
        List<Output> outputs = new ArrayList<>(recipes.size());
        for (SieveRecipe recipe : recipes) {
            Item item = recipe.result.getItem();
            if (recipe.result.isEmpty()) {
                continue;
            }
            // `result` is an ItemStack and its count is the fixed multiplier of every amount.
            long base = Math.max(1L, recipe.result.getCount());
            try {
                decompose(recipe.resultAmount, context, base, item, outputs);
            } catch (RuntimeException ignored) {
                // A number provider that needs loot context parameters this machine cannot supply
                // must not take the machine down; the spec only requires that it does not crash.
                // That output is skipped, everything else of the same recipe still yields.
            }
        }
        return outputs;
    }

    /** Drops the cache so the next lookup rebuilds from the current recipe manager. */
    public static void invalidate() {
        cachedManager = null;
        byMeshAndInput = Map.of();
    }

    /**
     * The empty loot context Ex Deorum uses when rolling a sieve recipe.
     *
     * <p>Built here rather than through Ex Deorum's helper so that this addon depends on nothing but
     * Ex Deorum's registries and recipe classes, the parts that have stayed stable across releases.
     */
    public static LootContext emptyLootContext(ServerLevel level) {
        return new LootContext.Builder(new LootParams(level, Map.of(), Map.of(), 0)).create(Optional.empty());
    }

    /**
     * The recipes that both accept {@code input} and work with {@code mesh}.
     *
     * <p>The mesh is deliberately not part of {@link SieveRecipe#matches}: it is tested separately
     * here, and the cache already narrowed the candidates down by mesh item and input item.
     */
    private static List<SieveRecipe> recipesFor(ServerLevel level, ItemStack mesh, ItemStack input) {
        if (level == null || mesh.isEmpty() || input.isEmpty()) {
            return List.of();
        }
        Map<Item, List<SieveRecipe>> byInput = cache(level.getRecipeManager()).get(mesh.getItem());
        if (byInput == null) {
            return List.of();
        }
        List<SieveRecipe> candidates = byInput.get(input.getItem());
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        RecipeInput recipeInput = new SingleInput(input);
        List<SieveRecipe> matching = new ArrayList<>(candidates.size());
        for (SieveRecipe recipe : candidates) {
            if (recipe.matches(recipeInput, level)) {
                matching.add(recipe);
            }
        }
        return matching;
    }

    private static Map<Item, Map<Item, List<SieveRecipe>>> cache(RecipeManager manager) {
        if (manager != cachedManager) {
            rebuild(manager);
        }
        return byMeshAndInput;
    }

    private static void rebuild(RecipeManager manager) {
        Map<Item, Map<Item, List<SieveRecipe>>> built = new HashMap<>();
        for (RecipeHolder<SieveRecipe> holder : manager.getAllRecipesFor(ERecipeTypes.SIEVE.get())) {
            SieveRecipe recipe = holder.value();
            if (recipe.byHandOnly()) {
                // A mechanical sieve yields nothing for these, so they are not worth remembering.
                continue;
            }
            for (ItemStack meshStack : recipe.mesh.getItems()) {
                Map<Item, List<SieveRecipe>> byInput =
                        built.computeIfAbsent(meshStack.getItem(), key -> new HashMap<>());
                for (ItemStack inputStack : recipe.ingredient.getItems()) {
                    byInput.computeIfAbsent(inputStack.getItem(), key -> new ArrayList<>()).add(recipe);
                }
            }
        }
        byMeshAndInput = built;
        cachedManager = manager;
    }

    /**
     * Splits one {@code result_amount} into a single chance/amount pair.
     *
     * <p>{@code result_amount} is the vanilla {@code NumberProviders} codec:
     * <ul>
     *   <li>{@code minecraft:binomial} - the shape every shipped Ex Deorum recipe uses. Its {@code n}
     *       and {@code p} are themselves number providers, so both are evaluated against the loot
     *       context; {@code p} becomes the drop chance and {@code n} the amount per success, which
     *       keeps the expected yield at exactly {@code n * p} per event.</li>
     *   <li>{@code minecraft:constant} - a certain amount.</li>
     *   <li>{@code minecraft:uniform} - collapsed to its mean, because a batch is one draw per
     *       output and a range cannot be carried through it.</li>
     *   <li>{@code exdeorum:summation} - the sum of its parts' means.</li>
     *   <li>anything else - evaluated as a plain amount rather than crashing.</li>
     * </ul>
     */
    private static void decompose(NumberProvider provider, LootContext context, long base, Item item,
            List<Output> outputs) {
        switch (provider) {
            case ConstantValue constant -> add(outputs, item, 1.0, scale(constant.value(), base));
            case UniformGenerator uniform -> {
                double min = uniform.min().getFloat(context);
                double max = uniform.max().getFloat(context);
                add(outputs, item, 1.0, scale((min + max) * 0.5, base));
            }
            case BinomialDistributionGenerator binomial -> {
                double p = binomial.p().getFloat(context);
                if (p <= 0.0) {
                    return;
                }
                add(outputs, item, Math.min(1.0, p), scale(binomial.n().getFloat(context), base));
            }
            case SummationGenerator summation -> add(outputs, item, 1.0, scale(mean(summation, context), base));
            default -> add(outputs, item, 1.0, scale(provider.getFloat(context), base));
        }
    }

    /** Expected value of a number provider, without the recipe's result stack count. */
    private static double mean(NumberProvider provider, LootContext context) {
        return switch (provider) {
            case ConstantValue constant -> constant.value();
            case UniformGenerator uniform ->
                    (uniform.min().getFloat(context) + uniform.max().getFloat(context)) * 0.5;
            case BinomialDistributionGenerator binomial ->
                    binomial.n().getFloat(context) * binomial.p().getFloat(context);
            case SummationGenerator summation -> {
                double sum = 0.0;
                for (NumberProvider part : summation.providers()) {
                    sum += mean(part, context);
                }
                yield sum;
            }
            default -> 0.0;
        };
    }

    private static void add(List<Output> outputs, Item item, double chance, long amount) {
        if (amount > 0L) {
            outputs.add(new Output(item, chance, amount));
        }
    }

    /** Rounds a provider value to a whole item amount and applies the result stack's count. */
    private static long scale(double value, long base) {
        if (!(value > 0.0) || base <= 0L) {
            return 0L;
        }
        long rounded = Math.round(value);
        if (rounded <= 0L) {
            return 0L;
        }
        return rounded > Long.MAX_VALUE / base ? Long.MAX_VALUE : rounded * base;
    }

    /** The single slot {@link SieveRecipe#matches} expects: the recipe never looks past slot 0. */
    private record SingleInput(ItemStack stack) implements RecipeInput {

        @Override
        public ItemStack getItem(int index) {
            return index == 0 ? stack : ItemStack.EMPTY;
        }

        @Override
        public int size() {
            return 1;
        }
    }
}
