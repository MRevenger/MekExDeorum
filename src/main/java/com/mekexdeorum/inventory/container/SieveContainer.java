package com.mekexdeorum.inventory.container;

import com.mekexdeorum.MekExDeorumConfig;
import com.mekexdeorum.SieveLayout;
import com.mekexdeorum.registry.MekExDeorumContainerTypes;
import com.mekexdeorum.tile.TileEntitySieve;
import mekanism.common.inventory.container.tile.MekanismTileContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;

/**
 * The machine's menu.
 *
 * <p>Every machine slot is contributed by the block entity: {@link MekanismTileContainer} walks the
 * tile's inventory and creates a menu slot for each entry, so the slot layout lives in exactly one
 * place ({@link SieveLayout}) and the client and the server compute the same rectangles.
 *
 * <p>The same class carries the GUI's action buttons. {@link #clickMenuButton} is vanilla's own
 * client-to-server route ({@code ServerboundContainerButtonClickPacket}), so a button in the screen
 * can change the machine without a packet of our own and behaves identically in single player and on
 * a dedicated server.
 */
public class SieveContainer extends MekanismTileContainer<TileEntitySieve> {

    /** Menu button id of the auto-split toggle in the screen. */
    public static final int BUTTON_SPLIT_INPUTS = 0;

    public SieveContainer(int id, Inventory inv, TileEntitySieve tile) {
        super(MekExDeorumContainerTypes.SIEVE, id, inv, tile);
    }

    @Override
    protected int getInventoryYOffset() {
        return SieveLayout.playerInventoryY(tile.getInputSlotCount(), MekExDeorumConfig.outputSlots());
    }

    /**
     * Runs a GUI action button on the server.
     *
     * <p>{@link #BUTTON_SPLIT_INPUTS} flips the machine's auto-split mode. The control is a
     * toggle rather than an action, so the screen only asks the server to flip it and then draws
     * whatever state the server reports back.
     */
    @Override
    public boolean clickMenuButton(@NotNull Player player, int id) {
        if (id != BUTTON_SPLIT_INPUTS) {
            return super.clickMenuButton(player, id);
        }
        if (!stillValid(player)) {
            return false;
        }
        tile.toggleAutoSplit();
        return true;
    }
}
