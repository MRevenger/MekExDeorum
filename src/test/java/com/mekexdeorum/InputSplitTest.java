package com.mekexdeorum;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link InputSplit}, the pure arithmetic behind the GUI's even-split button.
 *
 * <p>Every case states the three things that matter for the machine: the total of an item type is
 * preserved, the items are spread as evenly as the slot limits allow, and a second split changes
 * nothing.
 */
class InputSplitTest {

    /** Item max stack size used wherever the item's own limit is not the point of the test. */
    private static final int MAX = 64;

    @Test
    void evenSplitGivesEverySlotTheSameAmount() {
        // 12 items in the first of three slots: three lanes that can each run one operation.
        InputSplit.Result result = split(
                new int[] {0, -1, -1},
                new int[] {12, 0, 0},
                uniform(3, MAX),
                MAX,
                allSlots());
        assertCounts(new int[] {4, 4, 4}, result);
        assertEquals(12, total(result, 0), "no item may be lost or invented");
    }

    @Test
    void remainderGoesOnePerSlotFromTheLowestIndex() {
        // 12 over 5 slots: 12 / 5 = 2 with a remainder of 2, so the two lowest slots get one more.
        InputSplit.Result result = split(
                new int[] {0, -1, -1, -1, -1},
                new int[] {12, 0, 0, 0, 0},
                uniform(5, MAX),
                MAX,
                allSlots());
        assertCounts(new int[] {3, 3, 2, 2, 2}, result);
        assertEquals(12, total(result, 0), "the remainder is distributed, not dropped");
    }

    @Test
    void everyRemainderValueStillAddsUpToTheTotal() {
        for (int items = 1; items <= 40; items++) {
            for (int slots = 2; slots <= 9; slots++) {
                int[] counts = new int[slots];
                counts[0] = items;
                int[] groupOf = new int[slots];
                groupOf[0] = 0;
                for (int slot = 1; slot < slots; slot++) {
                    groupOf[slot] = -1;
                }
                InputSplit.Result result = InputSplit.distribute(
                        groupOf, counts, uniform(slots, MAX), group -> MAX, allSlots());
                assertEquals(items, total(result, 0), items + " items over " + slots + " slots");
                int lowest = Integer.MAX_VALUE;
                int highest = 0;
                int claimed = 0;
                for (int slot = 0; slot < slots; slot++) {
                    if (result.group()[slot] != 0) {
                        // Never claimed: the group had fewer items than slots.
                        continue;
                    }
                    claimed++;
                    lowest = Math.min(lowest, result.count()[slot]);
                    highest = Math.max(highest, result.count()[slot]);
                }
                // A group never claims more slots than it has items, so every claimed slot keeps one
                // and the spread can never be off by more than a single item.
                assertTrue(claimed >= 1, "the group lost its only slot at " + items + "/" + slots);
                assertTrue(lowest >= 1, "a claimed slot was left empty at " + items + "/" + slots);
                assertTrue(highest - lowest <= 1, "the spread is uneven at " + items + "/" + slots);
            }
        }
    }

    @Test
    void mixedItemTypesAreSplitIndependently() {
        // Slot 0 holds six of A, slot 1 two of B, slots 2 and 3 are free. A is served first and takes
        // both free slots; B keeps its single slot, because a different item is never overwritten.
        InputSplit.Result result = split(
                new int[] {0, 1, -1, -1},
                new int[] {6, 2, 0, 0},
                uniform(4, MAX),
                MAX,
                allSlots());
        assertArrayEquals(new int[] {0, 1, 0, 0}, result.group(), "the groups stay where they were");
        assertCounts(new int[] {2, 2, 2, 2}, result);
        assertEquals(6, total(result, 0), "group A keeps its six");
        assertEquals(2, total(result, 1), "group B keeps its two");
    }

    @Test
    void anItemTypeCannotGrowWhenEveryOtherSlotHoldsADifferentItem() {
        // Three different items, no free slot: nothing can move, so nothing changes.
        InputSplit.Result result = split(
                new int[] {0, 1, 2},
                new int[] {5, 6, 7},
                uniform(3, MAX),
                MAX,
                allSlots());
        assertCounts(new int[] {5, 6, 7}, result);
        assertArrayEquals(new int[] {0, 1, 2}, result.group(), "the groups stay where they were");
    }

    @Test
    void anEmptySlotIsOnlyGivenAnItemItAccepts() {
        // Slot 2 refuses everything, so the five items stay in slot 0 next to the accepting slot 1.
        InputSplit.Result result = InputSplit.distribute(
                new int[] {0, -1, -1},
                new int[] {5, 0, 0},
                uniform(3, MAX),
                group -> MAX,
                (slot, group) -> slot != 2);
        assertCounts(new int[] {3, 2, 0}, result);
        assertEquals(5, total(result, 0), "a refused slot never receives an item");
    }

    @Test
    void aShareIsCappedByTheItemMaxStackSize() {
        // 100 items across two slots, 50 each, is even and fits an item that stacks to 64.
        InputSplit.Result fitting = InputSplit.distribute(
                new int[] {0, 0},
                new int[] {60, 40},
                uniform(2, MAX),
                group -> MAX,
                allSlots());
        assertCounts(new int[] {50, 50}, fitting);
        assertEquals(100, total(fitting, 0), "the total is preserved");

        // The same pile of an item that stacks to 16 cannot be spread without dropping items, so the
        // group is left exactly as it is instead of being truncated to the cap.
        InputSplit.Result capping = InputSplit.distribute(
                new int[] {0, 0},
                new int[] {20, 20},
                uniform(2, MAX),
                group -> 16,
                allSlots());
        assertCounts(new int[] {20, 20}, capping);
        assertEquals(40, total(capping, 0), "the item's stack size never truncates the group");
    }

    @Test
    void theSlotOwnLimitWinsAndTheSurplusMovesToTheNextSlot() {
        // Slot 0 accepts three items only: it keeps three and the seven it cannot take follow into
        // slot 1, so the total is still ten and no item is dropped.
        InputSplit.Result result = InputSplit.distribute(
                new int[] {0, 0},
                new int[] {8, 2},
                new int[] {3, MAX},
                group -> MAX,
                allSlots());
        assertCounts(new int[] {3, 7}, result);
        assertEquals(10, total(result, 0), "the surplus is relocated, not lost");
    }

    @Test
    void aGroupThatDoesNotFitUnderItsLimitsIsLeftAlone() {
        // 20 items, one slot that takes 16 and one that takes 16: neither can hold the total, so the
        // split refuses to touch the group instead of dropping four items.
        InputSplit.Result result = InputSplit.distribute(
                new int[] {0, 0},
                new int[] {20, 20},
                new int[] {16, 16},
                group -> MAX,
                allSlots());
        assertCounts(new int[] {20, 20}, result);
        assertEquals(40, total(result, 0), "nothing may be dropped when the caps are too small");
    }

    @Test
    void aSingleSlotMachineHasNothingToSplit() {
        InputSplit.Result result = split(
                new int[] {0},
                new int[] {64},
                uniform(1, MAX),
                MAX,
                allSlots());
        assertCounts(new int[] {64}, result);
    }

    @Test
    void aBalancedLayoutNeedsNoRedistribution() {
        // "Is a redistribution needed at all?" - the decision the auto-split pass asks every time the
        // input slots change. An even row must answer "no", so no slot is written and the pass cannot
        // schedule itself again.
        InputSplit.Result balanced = split(
                new int[] {0, 0, 0},
                new int[] {5, 5, 5},
                uniform(3, MAX),
                MAX,
                allSlots());
        assertFalse(balanced.changed(), "an even row must not be redistributed");

        // Two types that each already use every slot they may use: nothing can be shared either.
        InputSplit.Result separated = split(
                new int[] {0, 1},
                new int[] {4, 6},
                uniform(2, MAX),
                MAX,
                allSlots());
        assertFalse(separated.changed(), "a row with no free slot to share must not be redistributed");
    }

    @Test
    void anUnbalancedLayoutNeedsARedistributionAndThenStops() {
        InputSplit.Result lopsided = split(
                new int[] {0, -1, -1},
                new int[] {12, 0, 0},
                uniform(3, MAX),
                MAX,
                allSlots());
        assertTrue(lopsided.changed(), "a pile sitting in one slot has to be spread");

        // Fed the balanced result back in, the same decision must now say "nothing to do".
        int[] after = lopsided.count();
        int[] groupsAfter = new int[after.length];
        for (int slot = 0; slot < after.length; slot++) {
            groupsAfter[slot] = after[slot] > 0 ? lopsided.group()[slot] : -1;
        }
        assertFalse(
                InputSplit.distribute(groupsAfter, after, uniform(after.length, MAX), group -> MAX, allSlots()).changed(),
                "the balanced result must report no further redistribution");
    }

    @Test
    void doingNothingIsANoOp() {
        // Every slot empty.
        InputSplit.Result empty = split(
                new int[] {-1, -1, -1},
                new int[] {0, 0, 0},
                uniform(3, MAX),
                MAX,
                allSlots());
        assertCounts(new int[] {0, 0, 0}, empty);
        assertArrayEquals(new int[] {-1, -1, -1}, empty.group(), "nothing was claimed");

        // Already even, and only one slot takes part: the split would change nothing.
        InputSplit.Result even = split(
                new int[] {0, 0, 0},
                new int[] {5, 5, 5},
                uniform(3, MAX),
                MAX,
                allSlots());
        assertCounts(new int[] {5, 5, 5}, even);
    }

    @Test
    void splittingTwiceChangesNothing() {
        int[][] starts = {{12, 0, 0}, {7, 1, 0}, {3, 0, 0, 0, 0}, {5, 6, 7}, {1, 1, 1, 30}};
        for (int[] start : starts) {
            int[] groupOf = new int[start.length];
            for (int slot = 0; slot < start.length; slot++) {
                groupOf[slot] = start[slot] > 0 ? (slot % 2) : -1;
            }
            InputSplit.Result first = InputSplit.distribute(
                    groupOf, start, uniform(start.length, MAX), group -> MAX, allSlots());
            // Feed the first result back in, the way the machine would see it one press later.
            int[] secondGroups = new int[start.length];
            for (int slot = 0; slot < start.length; slot++) {
                secondGroups[slot] = first.count()[slot] > 0 ? first.group()[slot] : -1;
            }
            InputSplit.Result second = InputSplit.distribute(
                    secondGroups, first.count(), uniform(start.length, MAX), group -> MAX, allSlots());
            assertArrayEquals(first.count(), second.count(), "the second split moved something");
            assertArrayEquals(first.group(), second.group(), "the second split regrouped something");
            assertFalse(second.changed(), "the second split still wanted to redistribute");
        }
    }

    @Test
    void theSplitNeverMovesItemsBetweenGroups() {
        InputSplit.Result result = split(
                new int[] {0, 1, -1, -1, -1},
                new int[] {9, 4, 0, 0, 0},
                uniform(5, MAX),
                MAX,
                allSlots());
        assertEquals(9, total(result, 0), "group A keeps its nine");
        assertEquals(4, total(result, 1), "group B keeps its four");
        assertTrue(result.group()[0] == 0 && result.group()[1] == 1, "the original slots keep their item");
    }

    // ---------------------------------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------------------------------

    private static InputSplit.Result split(
            int[] groupOf, int[] counts, int[] limits, int maxStack, InputSplit.Acceptor acceptor) {
        return InputSplit.distribute(groupOf, counts, limits, group -> maxStack, acceptor);
    }

    private static int[] uniform(int slots, int limit) {
        int[] limits = new int[slots];
        java.util.Arrays.fill(limits, limit);
        return limits;
    }

    private static InputSplit.Acceptor allSlots() {
        return (slot, group) -> true;
    }

    private static void assertCounts(int[] expected, InputSplit.Result result) {
        assertArrayEquals(expected, result.count(), "the split put the wrong number of items in a slot");
    }

    /** Items of one group after the split, so a test can prove nothing was created or dropped. */
    private static int total(InputSplit.Result result, int group) {
        int total = 0;
        for (int slot = 0; slot < result.count().length; slot++) {
            if (result.group()[slot] == group) {
                total += result.count()[slot];
            }
        }
        return total;
    }
}
