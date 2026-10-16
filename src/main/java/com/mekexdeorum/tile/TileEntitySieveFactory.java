package com.mekexdeorum.tile;

import mekanism.common.tier.FactoryTier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A factory tier of the sieve, produced by using a Mekanism tier installer on a plain sieve and then
 * upgraded tier by tier exactly like Mekanism's own factories.
 *
 * <p>It behaves like the plain machine, with more input slots and a larger energy buffer; the
 * parallelism still comes from the installed mesh alone.
 */
public class TileEntitySieveFactory extends TileEntitySieve {

    private final FactoryTier tier;

    public TileEntitySieveFactory(Holder<Block> blockProvider, BlockPos pos, BlockState state, FactoryTier tier) {
        super(blockProvider, pos, state);
        this.tier = tier;
    }

    /**
     * Machine tier: {@code FactoryTier.BASIC} has ordinal 0, so the offset by one is what makes the
     * four factories land on 1..4 and keeps {@code capacityForTier} in step with the registration.
     *
     * <p>The null check is not defensive noise: everything the superclass constructor builds is
     * derived from the block instead, but an overridden accessor can still be reached before this
     * field is assigned.
     */
    @Override
    protected int getTierIndex() {
        return tier == null ? super.getTierIndex() : tier.ordinal() + 1;
    }

    /** Parallel operations of this factory: 3, 5, 7 or 9. */
    @Override
    protected int getProcesses() {
        return tier == null ? super.getProcesses() : tier.processes;
    }
}
