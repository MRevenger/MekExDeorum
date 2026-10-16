package com.mekexdeorum.upgrade;

import java.util.List;
import mekanism.common.upgrade.IUpgradeData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

/**
 * Carries a machine's whole state across a tier-installer conversion, so that a sieve upgraded into
 * a factory - or into the next factory tier - loses neither items, nor progress, nor the overflow
 * buffer.
 *
 * <p>Contents are keyed by role rather than by raw slot index, because the layouts differ per tier:
 * the plain machine has one input slot and the factories three, five, seven or nine. Copying by
 * index would drop outputs into input slots.
 *
 * <p>Everything here is a detached copy: the data has to outlive the block entity it came from.
 * {@code overflowItems} and {@code overflowAmounts} are parallel lists, same order, same length -
 * an array of amounts rather than one field per stack, because a single entry may exceed
 * {@code Integer.MAX_VALUE}.
 */
public final class SieveUpgradeData implements IUpgradeData {

    public final ItemStack mesh;
    public final List<ItemStack> inputs;
    public final List<ItemStack> outputs;
    public final List<ItemStack> overflowItems;
    public final long[] overflowAmounts;
    public final ItemStack energyItem;
    public final long energy;
    public final int[] progress;
    public final int[] requiredTicks;
    public final CompoundTag components;

    public SieveUpgradeData(ItemStack mesh, List<ItemStack> inputs, List<ItemStack> outputs,
            List<ItemStack> overflowItems, long[] overflowAmounts, ItemStack energyItem, long energy,
            int[] progress, int[] requiredTicks, CompoundTag components) {
        this.mesh = mesh;
        this.inputs = inputs;
        this.outputs = outputs;
        this.overflowItems = overflowItems;
        this.overflowAmounts = overflowAmounts;
        this.energyItem = energyItem;
        this.energy = energy;
        this.progress = progress;
        this.requiredTicks = requiredTicks;
        this.components = components;
    }
}
