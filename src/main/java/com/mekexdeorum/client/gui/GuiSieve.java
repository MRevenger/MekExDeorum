package com.mekexdeorum.client.gui;

import com.mekexdeorum.MekExDeorumConfig;
import com.mekexdeorum.SieveLayout;
import com.mekexdeorum.inventory.container.SieveContainer;
import com.mekexdeorum.registry.MekExDeorumLang;
import com.mekexdeorum.tile.TileEntitySieve;
import mekanism.api.energy.IEnergyContainer;
import mekanism.client.gui.GuiConfigurableTile;
import mekanism.client.gui.element.bar.GuiVerticalPowerBar;
import mekanism.client.gui.element.button.ToggleButton;
import mekanism.common.util.MekanismUtils;
import mekanism.common.util.MekanismUtils.ResourceType;
import mekanism.common.util.text.BooleanStateDisplay.OnOff;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * The screen for the Universal Sieve and every factory tier.
 *
 * <p>The window is sized from the actual slot counts, so it always fits the configured layout, and
 * every overlay reads its coordinates from {@link SieveLayout} so a slot and its progress bar cannot
 * drift apart. The progress arrows are {@link GuiSieveProgress}, which draws Mekanism's downward
 * arrow inside the rectangle the layout gives it instead of at the sprite's own height, so an arrow
 * never reaches into the output grid below.
 *
 * <p>The background is Mekanism's own nine-sliced window texture, which stretches to any size, so no
 * custom GUI texture is needed.
 *
 * <p>The controls are Mekanism's own too: the side configuration tab of {@link GuiConfigurableTile},
 * which holds the player-facing auto-eject switch, and the auto-split toggle, a {@link ToggleButton}
 * that draws Mekanism's on/off switch texture and reads its state from the machine.
 */
public class GuiSieve extends GuiConfigurableTile<TileEntitySieve, SieveContainer> {

    /** Mekanism's on/off switch, off and on. Both sprites are 18x18. */
    private static final ResourceLocation TOGGLE = MekanismUtils.getResource(ResourceType.GUI_BUTTON, "toggle.png");
    private static final ResourceLocation TOGGLE_FLIPPED =
            MekanismUtils.getResource(ResourceType.GUI_BUTTON, "toggle_flipped.png");
    private static final int TOGGLE_TEXTURE = 18;

    /** Number of input slots of this machine, and therefore the number of progress arrows. */
    private final int inputSlots;

    private final int outputSlots;

    public GuiSieve(SieveContainer container, Inventory inv, Component title) {
        super(container, inv, title);
        inputSlots = tile.getInputSlotCount();
        outputSlots = MekExDeorumConfig.outputSlots();
        imageWidth = SieveLayout.guiWidth(inputSlots, outputSlots);
        imageHeight = SieveLayout.guiHeight(inputSlots, outputSlots);
        // Must be recomputed whenever the height changes, otherwise the label ends up outside the
        // window. Draw-only: the label is painted in the gap above the inventory, not on its first
        // row, and no slot coordinate depends on it.
        inventoryLabelY = SieveLayout.playerInventoryLabelY(inputSlots, outputSlots);
        // Lets Mekanism draw the container's slots, whose rectangles the block entity supplies.
        dynamicSlots = true;
    }

    @Override
    protected void addGuiElements() {
        super.addGuiElements();
        IEnergyContainer energy = tile.getEnergyContainer(0, null);
        // One Mekanism progress arrow directly under each input slot, each driven by its own input
        // bar. The arrow is drawn into the layout's rectangle, which is only as tall as the gap
        // above the output grid, so it cannot cover an output slot.
        for (int i = 0; i < inputSlots; i++) {
            int index = i;
            addRenderableWidget(new GuiSieveProgress(() -> tile.getProgress(index), this,
                    SieveLayout.progressBarX(i), SieveLayout.progressBarY(i),
                    SieveLayout.PROGRESS_WIDTH, SieveLayout.PROGRESS_HEIGHT));
        }
        // Vertical energy bar down the right hand edge, the way Mekanism's own machines show it.
        addRenderableWidget(new GuiVerticalPowerBar(this, energy, SieveLayout.powerBarX(inputSlots, outputSlots), SieveLayout.POWER_BAR_Y));
        // Auto-split toggle in the free strip left of the input row. Mekanism's switch texture shows
        // the state, the tooltip spells it out ("Auto-split inputs: On/Off"), and the state itself is
        // the machine's, synced from the server, so the widget never guesses.
        addRenderableWidget(new ToggleButton(this,
                SieveLayout.SPLIT_BUTTON_X, SieveLayout.SPLIT_BUTTON_Y,
                SieveLayout.SPLIT_BUTTON_SIZE, SieveLayout.SPLIT_BUTTON_SIZE,
                TOGGLE_TEXTURE, TOGGLE_TEXTURE, TOGGLE, TOGGLE_FLIPPED,
                tile::isAutoSplit, (element, mouseX, mouseY) -> requestAutoSplitToggle(),
                MekExDeorumLang.AUTO_SPLIT.translate(OnOff.of(true)),
                MekExDeorumLang.AUTO_SPLIT.translate(OnOff.of(false))));
    }

    /**
     * Asks the server to flip the auto-split mode.
     *
     * <p>The mode is server-authoritative: this only sends vanilla's container button packet, which
     * the server delivers to {@link SieveContainer#clickMenuButton}. That route works unchanged in
     * single player and on a dedicated server, and needs no packet of our own.
     *
     * @return whether the click was sent
     */
    private boolean requestAutoSplitToggle() {
        MultiPlayerGameMode gameMode = Minecraft.getInstance().gameMode;
        if (gameMode == null) {
            return false;
        }
        gameMode.handleInventoryButtonClick(menu.containerId, SieveContainer.BUTTON_SPLIT_INPUTS);
        return true;
    }
}
