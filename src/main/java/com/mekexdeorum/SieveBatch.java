package com.mekexdeorum;

import java.util.function.DoubleSupplier;

/**
 * The composition of one recipe output inside a batch sifting operation.
 *
 * <p>Pure Java, exactly like {@link SieveMath}: merging the {@code n} parallel sifting events of one
 * operation into a single binomial draw is the part of the machine that most needs a unit test, so
 * this class deliberately imports neither {@code net.minecraft} nor {@code mekanism}.
 *
 * <p>The block entity only walks the recipe's outputs, calls {@link #outputFor} for each of them and
 * merges the results into a {@code Map<Item, Long>}; all of the arithmetic lives here and in
 * {@link SieveMath}, which owns the binomial draw, the Fortune corrections and the chunk split.
 */
public final class SieveBatch {

    private SieveBatch() {}

    /**
     * How many items one recipe output yields for a batch of {@code n} sifting events.
     *
     * <p>{@code k ~ Binomial(n, p')} counts the successes of the whole batch and every success pays
     * {@code c'} items, so one call replaces {@code n} full sifting events while drawing exactly the
     * {@code n} random numbers {@link SieveMath#binomialSample} needs - no recipe lookup, no
     * {@code ItemStack} allocation and no event broadcast per item.
     *
     * @param n            sifting events merged into this batch, i.e. the mesh's parallelism clamped
     *                     to the input items actually available
     * @param p            the recipe's drop chance per single event
     * @param count        items per successful event
     * @param fortuneLevel Fortune level of the installed mesh
     * @param chanceBonus  chance added per Fortune level
     * @param amountBonus  relative amount added per Fortune level
     * @param rng          the level's random source, as a {@link DoubleSupplier} so that no Minecraft
     *                     type is needed here
     * @return the produced amount, never negative and never wrapping a {@code long}
     */
    public static long outputFor(int n, double p, long count, int fortuneLevel, double chanceBonus,
            double amountBonus, DoubleSupplier rng) {
        // Both corrections and the draw are SieveMath's; this only multiplies the two together.
        double chance = SieveMath.fortuneChance(p, fortuneLevel, chanceBonus);
        int successes = SieveMath.binomialSample(n, chance, rng);
        if (successes <= 0) {
            return 0L;
        }
        long amount = SieveMath.fortuneCount(count, fortuneLevel, amountBonus);
        return multiplyClamped(successes, amount);
    }

    /**
     * Adds {@code addend} to {@code total}, saturating at {@link Long#MAX_VALUE} instead of wrapping
     * around to a negative amount.
     *
     * <p>Used for the {@code Map<Item, Long>} merge of one batch: a wrapping sum would silently turn
     * a huge yield into "no items at all", which is worse than an unreachable cap.
     */
    public static long addClamped(long total, long addend) {
        if (addend <= 0L) {
            return total;
        }
        long sum = total + addend;
        return sum < total ? Long.MAX_VALUE : sum;
    }

    /** Multiplies two non-negative amounts, saturating at {@link Long#MAX_VALUE}. */
    public static long multiplyClamped(long factor, long amount) {
        if (factor <= 0L || amount <= 0L) {
            return 0L;
        }
        if (factor > Long.MAX_VALUE / amount) {
            return Long.MAX_VALUE;
        }
        return factor * amount;
    }
}
