package com.mekexdeorum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.DoubleSupplier;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SieveMath}. Every assertion is a property of the specification's algorithms, so
 * nothing here needs a Minecraft class path.
 */
class SieveMathTest {

    // --- binomialSample ---------------------------------------------------

    @Test
    void zeroProbabilityIsAlwaysZeroAndDrawsNothing() {
        AtomicInteger draws = new AtomicInteger();
        DoubleSupplier rng = () -> {
            draws.incrementAndGet();
            return 0.0;
        };

        assertEquals(0, SieveMath.binomialSample(64, 0.0, rng));
        assertEquals(0, SieveMath.binomialSample(64, -0.5, rng));
        assertEquals(0, draws.get(), "p <= 0 must not consume any random number");
    }

    @Test
    void certainProbabilityIsNAndDrawsNothing() {
        AtomicInteger draws = new AtomicInteger();
        DoubleSupplier rng = () -> {
            draws.incrementAndGet();
            return 0.999;
        };

        assertEquals(64, SieveMath.binomialSample(64, 1.0, rng));
        assertEquals(1, SieveMath.binomialSample(1, 2.0, rng));
        assertEquals(0, SieveMath.binomialSample(0, 1.0, rng));
        assertEquals(0, draws.get(), "p >= 1 must not consume any random number");
    }

    @Test
    void resultIsAlwaysWithinZeroAndN() {
        Random random = new Random(4242L);
        for (int n : new int[] {1, 4, 8, 16, 32, 64}) {
            for (double p : new double[] {0.01, 0.1, 0.5, 0.99}) {
                for (int trial = 0; trial < 200; trial++) {
                    int k = SieveMath.binomialSample(n, p, random::nextDouble);
                    assertTrue(k >= 0 && k <= n, "k=" + k + " out of range for n=" + n + " p=" + p);
                }
            }
        }
    }

    @Test
    void largeSampleMeanMatchesNp() {
        Random random = new Random(20250101L);
        int n = 1000;
        double p = 0.25;
        int trials = 1000;

        long total = 0;
        for (int trial = 0; trial < trials; trial++) {
            total += SieveMath.binomialSample(n, p, random::nextDouble);
        }

        // sigma of one sample is sqrt(n p (1-p)) ~ 13.7, so the mean of 1000 samples sits within
        // 0.44 of n*p; a tolerance of 3 is about 7 sigma and still catches a wrong p or a
        // "always return round(n*p)" shortcut.
        assertEquals(n * p, (double) total / trials, 3.0);
    }

    @Test
    void largeSampleVarianceMatchesTheBinomialVariance() {
        Random random = new Random(987654321L);
        int n = 1000;
        double p = 0.25;
        int trials = 2000;

        double sum = 0.0;
        double sumOfSquares = 0.0;
        for (int trial = 0; trial < trials; trial++) {
            int k = SieveMath.binomialSample(n, p, random::nextDouble);
            sum += k;
            sumOfSquares += (double) k * k;
        }
        double mean = sum / trials;
        double variance = sumOfSquares / trials - mean * mean;
        double expectedVariance = n * p * (1.0 - p);

        assertEquals(expectedVariance, variance, expectedVariance * 0.2);
    }

    // --- fortuneChance / fortuneCount -------------------------------------

    @Test
    void fortuneChanceAddsTheBonusPerLevelAndCapsAtOne() {
        assertEquals(0.1, SieveMath.fortuneChance(0.1, 0, 0.05), 1.0e-12);
        assertEquals(0.1, SieveMath.fortuneChance(0.1, 0, 0.5), 1.0e-12);
        assertEquals(0.2, SieveMath.fortuneChance(0.1, 2, 0.05), 1.0e-12);
        assertEquals(0.5, SieveMath.fortuneChance(0.2, 3, 0.1), 1.0e-12);
        assertEquals(1.0, SieveMath.fortuneChance(0.95, 5, 0.05), 1.0e-12);
        assertEquals(1.0, SieveMath.fortuneChance(1.0, 10, 1.0), 1.0e-12);
    }

    @Test
    void fortuneCountScalesTheAmountAndTruncates() {
        assertEquals(3L, SieveMath.fortuneCount(3L, 0, 0.5));
        assertEquals(3L, SieveMath.fortuneCount(3L, 5, 0.0));
        assertEquals(4L, SieveMath.fortuneCount(3L, 4, 0.125)); // 3 * 1.5 = 4.5 -> 4
        assertEquals(6L, SieveMath.fortuneCount(3L, 2, 0.5));
        assertEquals(0L, SieveMath.fortuneCount(0L, 3, 1.0));
    }

    // --- requiredTicks ----------------------------------------------------

    @Test
    void requiredTicksIsReducedPerEfficiencyLevel() {
        assertEquals(100, SieveMath.requiredTicks(100, 0, 0.15));
        assertEquals(87, SieveMath.requiredTicks(100, 1, 0.15)); // ceil(86.95)
        assertEquals(69, SieveMath.requiredTicks(100, 3, 0.15)); // ceil(68.97)
        assertEquals(58, SieveMath.requiredTicks(100, 5, 0.15)); // ceil(57.14)
        assertEquals(1, SieveMath.requiredTicks(1, 20, 0.15));
    }

    @Test
    void requiredTicksWithNoBonusIsTheBaseTime() {
        for (int base : new int[] {1, 20, 100, 72000}) {
            assertEquals(base, SieveMath.requiredTicks(base, 8, 0.0));
        }
    }

    // --- forEachChunk -----------------------------------------------------

    @Test
    void forEachChunkSplitsExactlyAtTheChunkLimit() {
        assertEquals(Integer.MAX_VALUE, SieveMath.MAX_CHUNK);

        assertEquals(List.of((long) Integer.MAX_VALUE), chunksOf(Integer.MAX_VALUE));
        assertEquals(List.of(2147483647L, 1L), chunksOf(2147483648L));
        assertEquals(List.of(2147483647L, 2147483647L), chunksOf(4294967294L));
        assertEquals(List.of(2147483647L, 2147483647L, 1L), chunksOf(4294967295L));
    }

    @Test
    void forEachChunkConservesTheTotal() {
        for (long amount : new long[] {1L, 63L, 64L, 99L, 1000L, 4294967295L, 10_000_000_000L}) {
            List<Long> chunks = chunksOf(amount);
            long sum = 0L;
            for (long chunk : chunks) {
                assertTrue(chunk > 0L && chunk <= SieveMath.MAX_CHUNK, "bad chunk " + chunk);
                sum += chunk;
            }
            assertEquals(amount, sum, "chunk sum lost items for amount " + amount);
        }
    }

    @Test
    void forEachChunkIgnoresZeroAndNegativeAmounts() {
        AtomicInteger calls = new AtomicInteger();
        SieveMath.forEachChunk(0L, chunk -> calls.incrementAndGet());
        SieveMath.forEachChunk(-1L, chunk -> calls.incrementAndGet());
        SieveMath.forEachChunk(Long.MIN_VALUE, chunk -> calls.incrementAndGet());
        assertEquals(0, calls.get());
    }

    private static List<Long> chunksOf(long amount) {
        List<Long> chunks = new ArrayList<>();
        SieveMath.forEachChunk(amount, chunks::add);
        return chunks;
    }
}
