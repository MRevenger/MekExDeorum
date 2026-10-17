package com.mekexdeorum;

import java.util.ArrayList;
import java.util.List;

/**
 * Even split of a machine's input slots: the arithmetic only, no Minecraft type at all, so the
 * distribution rules below are unit-testable without a game.
 *
 * <p>This mirrors Mekanism's own factory rebalancing,
 * {@code TileEntityFactory#distributeItems} ({@code mekanism/common/tile/factory/TileEntityFactory.java:627},
 * 10.7.11.76):
 *
 * <pre>
 * int numberPerSlot = totalCount / processCount;
 * int remainder = totalCount % processCount;
 * // ...then, per participating slot, sizeForSlot = numberPerSlot, plus one more while a
 * // remainder is left, and the result is written with setStackUnchecked.
 * </pre>
 *
 * <p>A sieve machine runs every input slot as its own lane, so an input pile has to be spread over
 * as many lanes as possible for the machine to work in parallel: that is exactly what this class
 * computes. The rules, in order:
 *
 * <ol>
 *   <li><b>Groups.</b> Every slot that holds something belongs to the group of its item. Groups are
 *       numbered by first appearance, and the caller decides what "the same item" means.</li>
 *   <li><b>Participants.</b> A group's participants are the slots that already hold its item plus
 *       the empty slots the group is allowed to use. An empty slot goes to at most one group; the
 *       groups are served in first-appearance order and each takes the lowest free slot index that
 *       accepts its item. A slot that holds a different item is never taken away from it.</li>
 *   <li><b>How many slots a group takes.</b> Only as many as it can give at least one item to
 *       ({@code size < total}); a slot that would end up empty is not worth claiming, and the free
 *       slots are then still available to the next group.</li>
 *   <li><b>Shares.</b> {@code numberPerSlot = total / participants} and {@code remainder =
 *       total % participants}; the first {@code remainder} participants (lowest slot index) get one
 *       item more. Because a group never takes more slots than it has items, {@code numberPerSlot}
 *       is at least one, so a split never empties a slot that already held something.</li>
 *   <li><b>Clamp.</b> A share is capped at the item's max stack size and at the slot's own limit. If
 *       the caps leave items over, the surplus is handed to the following participants that still
 *       have room. If even that cannot place everything, the group is left exactly as it is: losing
 *       items is never an option.</li>
 *   <li><b>No-op and idempotent.</b> Nothing to do gives back the input unchanged, and because the
 *       participants of the result are the same as those of the input, running the split twice
 *       changes nothing the second time.</li>
 * </ol>
 */
public final class InputSplit {

    /** Highest stack the group's item allows, normally {@code ItemStack#getMaxStackSize}. */
    @FunctionalInterface
    public interface GroupMax {

        int maxOf(int group);
    }

    /** Whether one input slot may hold one group's item: the slot's own validity check. */
    @FunctionalInterface
    public interface Acceptor {

        boolean test(int slot, int group);
    }

    /**
     * What every slot should hold after the split, both arrays indexed by slot: the group of the
     * item ({@code -1} for "nothing", which is also what an unclaimed empty slot keeps) and the
     * count. Slots the split does not touch carry their input values.
     *
     * <p>{@code changed} is the "is a redistribution needed at all?" decision: {@code false} when the
     * layout is already balanced, in which case the caller must not touch a single slot. That is what
     * turns the auto-split mode into a once-per-change pass instead of a once-per-tick one.
     */
    public record Result(int[] group, int[] count, boolean changed) {}

    private InputSplit() {}

    /**
     * Spreads every group's items over that group's participating slots.
     *
     * @param groupOf per slot, the item group it holds, {@code -1} when the slot is empty
     * @param count   per slot, how many items it holds; ignored for an empty slot
     * @param limit   per slot, the most the slot itself accepts
     * @param groupMax per group, the item's max stack size
     * @param acceptor per (slot, group), whether that slot may hold that group's item
     */
    public static Result distribute(
            int[] groupOf, int[] count, int[] limit, GroupMax groupMax, Acceptor acceptor) {
        int slots = count.length;
        if (groupOf.length != slots || limit.length != slots) {
            throw new IllegalArgumentException("groupOf, count and limit must have one entry per slot");
        }
        int[] targetGroup = groupOf.clone();
        int[] targetCount = count.clone();
        if (slots == 0) {
            return new Result(targetGroup, targetCount, false);
        }

        int groups = 0;
        for (int group : groupOf) {
            groups = Math.max(groups, group + 1);
        }
        if (groups == 0) {
            return new Result(targetGroup, targetCount, false);
        }

        int[] total = new int[groups];
        List<List<Integer>> participants = new ArrayList<>(groups);
        for (int group = 0; group < groups; group++) {
            participants.add(new ArrayList<>());
        }
        for (int slot = 0; slot < slots; slot++) {
            int group = groupOf[slot];
            if (group >= 0 && group < groups && count[slot] > 0) {
                participants.get(group).add(slot);
                total[group] += count[slot];
            }
        }

        // The empty slots, handed out per group in first-appearance order and by ascending slot index.
        boolean[] claimed = new boolean[slots];
        for (int group = 0; group < groups; group++) {
            List<Integer> groupSlots = participants.get(group);
            for (int slot = 0; slot < slots && groupSlots.size() < total[group]; slot++) {
                if (groupOf[slot] >= 0 || claimed[slot] || !acceptor.test(slot, group)) {
                    continue;
                }
                groupSlots.add(slot);
                claimed[slot] = true;
            }
            // Sorted, so the shares depend on the set of participants and not on the order they were
            // found in: that is what makes a second split a no-op.
            groupSlots.sort(null);
        }

        for (int group = 0; group < groups; group++) {
            List<Integer> groupSlots = participants.get(group);
            int size = groupSlots.size();
            if (size == 0 || total[group] <= 0 || size == 1) {
                // Nothing to even out: the single slot already holds the group's whole total.
                continue;
            }
            int numberPerSlot = total[group] / size;
            int remainder = total[group] % size;
            int itemMax = groupMax.maxOf(group);
            int[] want = new int[size];
            int assigned = 0;
            for (int index = 0; index < size; index++) {
                int capacity = Math.min(itemMax, limit[groupSlots.get(index)]);
                want[index] = Math.min(numberPerSlot + (index < remainder ? 1 : 0), Math.max(0, capacity));
                assigned += want[index];
            }
            // Whatever the caps refused goes to the participants that still have room, in slot order.
            int leftOver = total[group] - assigned;
            for (int index = 0; index < size && leftOver > 0; index++) {
                int capacity = Math.min(itemMax, limit[groupSlots.get(index)]);
                int room = capacity - want[index];
                if (room > 0) {
                    int add = Math.min(room, leftOver);
                    want[index] += add;
                    leftOver -= add;
                }
            }
            if (leftOver > 0) {
                // The caps cannot hold this group without dropping items: leave it untouched.
                continue;
            }
            for (int index = 0; index < size; index++) {
                int slot = groupSlots.get(index);
                targetGroup[slot] = group;
                targetCount[slot] = want[index];
            }
        }
        boolean changed = false;
        for (int slot = 0; slot < slots && !changed; slot++) {
            changed = targetGroup[slot] != groupOf[slot] || targetCount[slot] != count[slot];
        }
        return new Result(targetGroup, targetCount, changed);
    }
}
