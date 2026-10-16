package com.mekexdeorum.client.gui;

import com.mekexdeorum.MekExDeorumConfig;
import com.mekexdeorum.SieveLayout;
import com.mekexdeorum.inventory.container.SieveContainer;
import com.mekexdeorum.tile.TileEntitySieve;
import mekanism.api.energy.IEnergyContainer;
import mekanism.client.gui.GuiConfigurableTile;
import mekanism.client.gui.element.bar.GuiVerticalPowerBar;
import mekanism.client.gui.element.progress.GuiProgress;
import mekanism.client.gui.element.progress.ProgressType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * The screen for the Universal Sieve and every factory tier.
 *
 * <p>The window is sized from the actual slot counts, so it always fits the configured layout, and
 * every overlay reads its coordinates from {@link SieveLayout} so a slot and its progress bar cannot
 * drift apart.
 *
 * <p>The background is Mekanism's own nine-sliced window texture, which stretches to any size, so no
 * custom GUI texture is needed.
 */
public class GuiSieve extends GuiConfigurableTile<TileEntitySieve, SieveContainer> {

    /** Number of input slots of this machine, and therefore the number of progress arrows. */
    private final int inputSlots;

    private final int outputSlots;

    public GuiSieve(SieveContainer container, Inventory inv, Component title) {
        super(container, inv, title);
        inputSlots = tile.getInputSlotCount();
        outputSlots = MekExDeorumConfig.outputSlots();
        imageWidth = SieveLayout.guiWidth(inputSlots, outputSlots);
        imageHeight = SieveLayout.guiHeight(inputSlots, outputSlots);
        // Must be recomputed whenever the height changes, otherwise the label ends up outside the window.
        inventoryLabelY = SieveLayout.playerInventoryY(inputSlots, outputSlots);
        // Lets Mekanism draw the container's slots, whose rectangles the block entity supplies.
        dynamicSlots = true;
    }

    @Override
    protected void addGuiElements() {
        super.addGuiElements();
        IEnergyContainer energy = tile.getEnergyContainer(0, null);
        // One Mekanism progress arrow directly under each input slot, each driven by its own input bar.
        for (int i = 0; i < inputSlots; i++) {
            int index = i;
            addRenderableWidget(new GuiProgress(() -> tile.getProgress(index), ProgressType.DOWN, this,
                    SieveLayout.progressBarX(i), SieveLayout.progressBarY(i)));
        }
        // Vertical energy bar down the right hand edge, the way Mekanism's own machines show it.
        addRenderableWidget(new GuiVerticalPowerBar(this, energy, SieveLayout.powerBarX(inputSlots, outputSlots), SieveLayout.POWER_BAR_Y));
    }
}
