package com.mekexdeorum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.DoubleSupplier;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SieveBatch}, the composition of one recipe output of a batch sifting operation.
 *
 * <p>Every assertion is a property of the batch algorithm - how the binomial draw, the Fortune
 * corrections and the amount multiplier combine, and how many random numbers one output costs - so
 * nothing here needs a Minecraft class path.
 */
class SieveBatchTest {

    // --- composition ------------------------------------------------------

    @Test
    void zeroChanceYieldsNothingAndDrawsNothing() {
        AtomicInteger draws = new AtomicInteger();
        DoubleSupplier rng = () -> {
            draws.incrementAndGet();
            return 0.0;
        };

        assertEquals(0L, SieveBatch.outputFor(64, 0.0, 1L, 0, 0.0, 0.0, rng));
        assertEquals(0L, SieveBatch.outputFor(64, -0.25, 1L, 0, 0.0, 0.0, rng));
        assertEquals(0, draws.get(), "a chance of zero must not consume a random number");
    }

    @Test
    void certainChancePaysEveryEventAndDrawsNothing() {
        AtomicInteger draws = new AtomicInteger();
        DoubleSupplier rng = () -> {
            draws.incrementAndGet();
            return 0.5;
        };

        // 64 events, 3 items each, no Fortune bonus: every event pays.
        assertEquals(192L, SieveBatch.outputFor(64, 1.0, 3L, 0, 0.0, 0.0, rng));
        assertEquals(0, draws.get(), "a chance of one must not consume a random number");
    }

    @Test
    void nothingIsProducedWithoutEventsOrWithoutItems() {
        Random random = new Random(7L);
        assertEquals(0L, SieveBatch.outputFor(0, 0.5, 5L, 0, 0.0, 0.0, random::nextDouble));
        assertEquals(0L, SieveBatch.outputFor(64, 0.5, 0L, 0, 0.0, 0.0, random::nextDouble));
        assertEquals(0L, SieveBatch.outputFor(64, 0.5, -2L, 0, 0.0, 0.0, random::nextDouble));
        assertEquals(0L, SieveBatch.outputFor(-4, 0.5, 5L, 0, 0.0, 0.0, random::nextDouble));
    }

    @Test
    void resultNeverExceedsOneAmountPerEvent() {
        Random random = new Random(1234L);
        for (int n : new int[] {1, 4, 8, 16, 32, 64}) {
            for (double p : new double[] {0.01, 0.3, 0.9, 1.0}) {
                long produced = SieveBatch.outputFor(n, p, 2L, 0, 0.0, 0.0, random::nextDouble);
                assertTrue(produced >= 0L, "negative yield for n=" + n + " p=" + p);
                assertTrue(produced <= 2L * n, "yield " + produced + " above the maximum for n=" + n);
            }
        }
    }

    // --- Fortune ----------------------------------------------------------

    @Test
    void fortuneRaisesTheChance() {
        // p = 0.5 is already certain once the chance bonus pushes it to 1.0.
        Random certain = new Random(11L);
        assertEquals(64L * 2L, SieveBatch.outputFor(64, 0.5, 2L, 10, 0.05, 0.0, certain::nextDouble));

        // Without Fortune the same batch is a coin flip per event.
        Random plain = new Random(11L);
        long unfortuned = SieveBatch.outputFor(64, 0.5, 2L, 0, 0.05, 0.0, plain::nextDouble);
        assertTrue(unfortuned < 64L * 2L, "the bonus must be what makes every event pay");
    }

    @Test
    void fortuneOfZeroLeavesTheChanceAlone() {
        Random withBonus = new Random(99L);
        Random withoutBonus = new Random(99L);
        assertEquals(
                SieveBatch.outputFor(64, 0.25, 2L, 0, 0.05, 0.0, withoutBonus::nextDouble),
                SieveBatch.outputFor(64, 0.25, 2L, 0, 0.0, 0.0, withBonus::nextDouble),
                "level 0 Fortune must not change anything, whatever the configured bonus");
    }

    @Test
    void amountBonusScalesTheItemsPerSuccess() {
        Random random = new Random(5150L);
        // count 2 with +50% per level at level 2 is 2 * (1 + 1.0) = 4, and every event pays.
        assertEquals(4L, SieveBatch.outputFor(1, 1.0, 2L, 2, 0.0, 0.5, random::nextDouble));
        assertEquals(8L, SieveBatch.outputFor(2, 1.0, 2L, 2, 0.0, 0.5, random::nextDouble));
        // Amount bonus truncates towards zero, like SieveMath.fortuneCount: 3 * 1.5 = 4.5 -> 4.
        assertEquals(4L, SieveBatch.outputFor(1, 1.0, 3L, 4, 0.0, 0.125, random::nextDouble));
    }

    // --- the random number budget -----------------------------------------

    @Test
    void oneOutputCostsExactlyNDraws() {
        for (int n : new int[] {1, 4, 8, 16, 32, 64}) {
            AtomicInteger draws = new AtomicInteger();
            DoubleSupplier rng = () -> {
                draws.incrementAndGet();
                return 0.999;
            };
            SieveBatch.outputFor(n, 0.5, 1L, 0, 0.0, 0.0, rng);
            assertEquals(n, draws.get(), "n=" + n + " must cost exactly n nextDouble() calls");
        }
    }

    @Test
    void anUnreachableChanceCapsAtZeroDraws() {
        AtomicInteger draws = new AtomicInteger();
        DoubleSupplier rng = () -> {
            draws.incrementAndGet();
            return 0.0;
        };
        // 0.9 + 5 levels * 0.05 reaches 1.0, so SieveMath short circuits the whole draw.
        assertEquals(64L, SieveBatch.outputFor(64, 0.9, 1L, 5, 0.05, 0.0, rng));
        assertEquals(0, draws.get());
    }

    // --- statistical properties -------------------------------------------

    @Test
    void largeSampleMeanMatchesNpTimesTheAmount() {
        Random random = new Random(20250102L);
        int n = 1000;
        double p = 0.25;
        long count = 3L;
        int trials = 400;

        long total = 0L;
        for (int trial = 0; trial < trials; trial++) {
            total += SieveBatch.outputFor(n, p, count, 0, 0.0, 0.0, random::nextDouble);
        }

        double expected = (double) n * p * count;
        // One sample has sigma sqrt(n p (1-p)) * count ~ 41, so the mean of 400 samples sits within
        // about 2; a tolerance of 20 is ~10 sigma and still catches a wrong p or a wrong amount.
        assertEquals(expected, (double) total / trials, 20.0);
    }

    @Test
    void largeSampleMeanMatchesTheFortuneCorrectedChance() {
        Random random = new Random(31415926L);
        int n = 1000;
        int fortune = 3;
        double chanceBonus = 0.1;
        long count = 2L;
        int trials = 400;

        double p = SieveMath.fortuneChance(0.1, fortune, chanceBonus);
        assertEquals(0.4, p, 1.0e-12);

        long total = 0L;
        for (int trial = 0; trial < trials; trial++) {
            total += SieveBatch.outputFor(n, 0.1, count, fortune, chanceBonus, 0.0, random::nextDouble);
        }

        double expected = (double) n * p * count;
        assertEquals(expected, (double) total / trials, expected * 0.05);
    }

    // --- saturating helpers -----------------------------------------------

    @Test
    void addClampedSaturatesInsteadOfWrapping() {
        assertEquals(3L, SieveBatch.addClamped(1L, 2L));
        assertEquals(1L, SieveBatch.addClamped(1L, 0L));
        assertEquals(1L, SieveBatch.addClamped(1L, -5L));
        assertEquals(Long.MAX_VALUE, SieveBatch.addClamped(Long.MAX_VALUE - 1L, 5L));
        assertEquals(Long.MAX_VALUE, SieveBatch.addClamped(Long.MAX_VALUE, Long.MAX_VALUE));
    }

    @Test
    void multiplyClampedSaturatesInsteadOfWrapping() {
        assertEquals(12L, SieveBatch.multiplyClamped(3L, 4L));
        assertEquals(0L, SieveBatch.multiplyClamped(0L, 4L));
        assertEquals(0L, SieveBatch.multiplyClamped(3L, 0L));
        assertEquals(0L, SieveBatch.multiplyClamped(-3L, 4L));
        assertEquals(Long.MAX_VALUE, SieveBatch.multiplyClamped(Long.MAX_VALUE, 2L));
        assertEquals(Long.MAX_VALUE, SieveBatch.multiplyClamped(2L, Long.MAX_VALUE));
    }

    @Test
    void oneBatchMergedPerItemNeverWraps() {
        long merged = SieveBatch.addClamped(SieveBatch.multiplyClamped(Long.MAX_VALUE, 2L), 4L);
        assertEquals(Long.MAX_VALUE, merged);
    }
}
