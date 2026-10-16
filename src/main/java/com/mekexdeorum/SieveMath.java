package com.mekexdeorum;

import java.util.function.DoubleSupplier;
import java.util.function.LongConsumer;

/**
 * The sieving maths, shared by the block entity and by tests.
 *
 * <p>Deliberately free of every {@code net.minecraft} and {@code mekanism} type: merging the N
 * parallel sifting events of one operation into a single binomial draw is the part of the machine
 * that most needs unit tests, and it must be testable without a Minecraft class path.
 *
 * <p>The random source is passed in as a {@link DoubleSupplier}, so the caller can hand over the
 * level's {@code RandomSource::nextDouble} without leaking Minecraft into this class.
 */
public final class SieveMath {

    /** Largest amount that can be added to an oversized output slot in one go. */
    public static final int MAX_CHUNK = Integer.MAX_VALUE;

    private SieveMath() {}

    /**
     * Number of successes in {@code n} independent Bernoulli trials of probability {@code p}.
     *
     * <p>{@code p <= 0} consumes no random numbers and yields 0; {@code p >= 1} consumes none and
     * yields {@code n}. Otherwise exactly {@code n} numbers are drawn, which is what makes a batch
     * of {@code n} sieving events cost {@code n} comparisons instead of {@code n} full events.
     */
    public static int binomialSample(int n, double p, DoubleSupplier rng) {
        if (p <= 0.0) {
            return 0;
        }
        if (p >= 1.0) {
            return Math.max(0, n);
        }
        int successes = 0;
        for (int trial = 0; trial < n; trial++) {
            if (rng.getAsDouble() < p) {
                successes++;
            }
        }
        return successes;
    }

    /** Drop chance of one result after the Fortune chance bonus, never above 1.0. */
    public static double fortuneChance(double p, int fortuneLevel, double chanceBonus) {
        if (fortuneLevel <= 0) {
            return Math.min(1.0, p);
        }
        return Math.min(1.0, p + chanceBonus * fortuneLevel);
    }

    /** Result amount per success after the Fortune amount bonus, truncated towards zero. */
    public static long fortuneCount(long count, int fortuneLevel, double amountBonus) {
        if (fortuneLevel <= 0) {
            return count;
        }
        return (long) (count * (1.0 + amountBonus * fortuneLevel));
    }

    /** Ticks one operation takes with the given Efficiency level; scales with the level. */
    public static int requiredTicks(int baseTicks, int efficiencyLevel, double speedBonus) {
        double speedMultiplier = 1.0 + efficiencyLevel * speedBonus;
        if (!(speedMultiplier > 0.0)) {
            return Math.max(0, baseTicks);
        }
        return (int) Math.ceil(baseTicks / speedMultiplier);
    }

    /**
     * Splits a {@code long} amount into chunks of at most {@link #MAX_CHUNK} and hands each chunk to
     * the consumer, in order. The sum of the chunks is exactly {@code amount}, so no remainder is
     * lost when a batch produces more items than one oversized slot can take at once.
     *
     * <p>An amount of zero or less produces no call at all.
     */
    public static void forEachChunk(long amount, LongConsumer consumer) {
        while (amount > 0L) {
            long chunk = Math.min(amount, (long) MAX_CHUNK);
            consumer.accept(chunk);
            amount -= chunk;
        }
    }
}
