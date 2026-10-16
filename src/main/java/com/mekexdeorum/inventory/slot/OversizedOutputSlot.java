package com.mekexdeorum.inventory.slot;

import mekanism.api.IContentsListener;
import mekanism.api.functions.ConstantPredicates;
import mekanism.common.inventory.container.slot.ContainerSlotType;
import mekanism.common.inventory.slot.BasicInventorySlot;
import org.jetbrains.annotations.Nullable;

/**
 * An output slot that may hold far more than a vanilla stack.
 *
 * <p>{@link BasicInventorySlot#getLimit} clamps to the item's own max stack size while {@code
 * obeyStackLimit} is on, which would cap a slot at 64 and - worse - make the machine believe it is
 * full the moment a slot holds 64 items. Clearing {@code obeyStackLimit} replaces that clamp with
 * this slot's own limit, which is why the "is there room" test in the block entity must ask
 * {@link #getLimit(ItemStack)} and never {@code Math.min(item.getMaxStackSize(), limit)}.
 *
 * <p>Counts above 99 are safe here because {@link BasicInventorySlot} serialises through
 * Mekanism's {@code SerializerHelper.saveOversized}, which bypasses the vanilla 99-item codec limit,
 * and syncs the count as a varint that has no such limit.
 */
public class OversizedOutputSlot extends BasicInventorySlot {

    /** Hard limit of one output slot: {@code Integer.MAX_VALUE} items. */
    public static final int SLOT_LIMIT = Integer.MAX_VALUE;

    public static OversizedOutputSlot at(@Nullable IContentsListener listener, int x, int y) {
        return new OversizedOutputSlot(listener, x, y);
    }

    private OversizedOutputSlot(@Nullable IContentsListener listener, int x, int y) {
        super(SLOT_LIMIT, ConstantPredicates.alwaysTrueBi(), ConstantPredicates.internalOnly(),
                ConstantPredicates.alwaysTrue(), listener, x, y);
        // The machine inserts with AutomationType.INTERNAL and must be able to push more than a
        // vanilla stack into one slot; player and pipe access stays extraction only.
        this.obeyStackLimit = false;
        setSlotType(ContainerSlotType.OUTPUT);
    }
}
