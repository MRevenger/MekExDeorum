package com.mekexdeorum.inventory.container;

import com.mekexdeorum.MekExDeorumConfig;
import com.mekexdeorum.SieveLayout;
import com.mekexdeorum.registry.MekExDeorumContainerTypes;
import com.mekexdeorum.tile.TileEntitySieve;
import mekanism.common.inventory.container.tile.MekanismTileContainer;
import net.minecraft.world.entity.player.Inventory;

/**
 * The machine's menu.
 *
 * <p>Every machine slot is contributed by the block entity: {@link MekanismTileContainer} walks the
 * tile's inventory and creates a menu slot for each entry, so the slot layout lives in exactly one
 * place ({@link SieveLayout}) and the client and the server compute the same rectangles.
 */
public class SieveContainer extends MekanismTileContainer<TileEntitySieve> {

    public SieveContainer(int id, Inventory inv, TileEntitySieve tile) {
        super(MekExDeorumContainerTypes.SIEVE, id, inv, tile);
    }

    @Override
    protected int getInventoryYOffset() {
        return SieveLayout.playerInventoryY(tile.getInputSlotCount(), MekExDeorumConfig.outputSlots());
    }
}
