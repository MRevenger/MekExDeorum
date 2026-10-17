package com.mekexdeorum;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests for the even-split button's place in {@link SieveLayout}: it must never cover a slot, a
 * progress arrow, the energy bar or the player inventory, at any tier and any output count.
 *
 * <p>Coordinates only, no Minecraft type, so the placement is checkable without starting the game.
 */
class SplitButtonLayoutTest {

    /** Input slots of the five tiers: Universal Sieve, Basic, Advanced, Elite, Ultimate factory. */
    private static final int[] INPUT_SLOTS = {1, 3, 5, 7, 9};

    @Test
    void theButtonSitsInsideTheWindowForEveryTierAndOutputCount() {
        for (int inputs : INPUT_SLOTS) {
            for (int outputs = 1; outputs <= 64; outputs++) {
                Rect button = button();
                String where = " at " + inputs + " inputs and " + outputs + " output slots";
                assertTrue(button.x() >= 0 && button.y() >= 0, "the button starts outside the window" + where);
                assertTrue(
                        button.right() <= SieveLayout.guiWidth(inputs, outputs),
                        "the button sticks out to the right" + where);
                assertTrue(
                        button.bottom() <= SieveLayout.guiHeight(inputs, outputs),
                        "the button sticks out at the bottom" + where);
                assertTrue(
                        button.bottom() <= SieveLayout.machineBottom(inputs, outputs),
                        "the button reaches into the player inventory area" + where);
            }
        }
    }

    @Test
    void theButtonCoversNoSlotAndNoProgressArrow() {
        Rect mesh = new Rect(SieveLayout.MESH_X, SieveLayout.MESH_Y, SieveLayout.SLOT, SieveLayout.SLOT);
        Rect energy = new Rect(SieveLayout.ENERGY_X, SieveLayout.ENERGY_Y, SieveLayout.SLOT, SieveLayout.SLOT);
        for (int inputs : INPUT_SLOTS) {
            for (int outputs = 1; outputs <= 64; outputs++) {
                Rect button = button();
                String where = " at " + inputs + " inputs and " + outputs + " output slots";
                assertFalse(button.intersects(mesh), "the button covers the mesh slot" + where);
                assertFalse(button.intersects(energy), "the button covers the energy slot" + where);
                for (int i = 0; i < inputs; i++) {
                    assertFalse(button.intersects(input(i)), "the button covers input slot " + i + where);
                    assertFalse(button.intersects(arrow(i)), "the button covers progress arrow " + i + where);
                }
                for (int slot = 0; slot < outputs; slot++) {
                    assertFalse(
                            button.intersects(output(slot, inputs, outputs)),
                            "the button covers output slot " + slot + where);
                }
                // And it does not hang over the player's own inventory, which starts at y
                // playerInventoryY and x 8 (MekanismContainer's first player column).
                Rect inventory = new Rect(8, SieveLayout.playerInventoryY(inputs, outputs), 9 * SieveLayout.SLOT, 76);
                assertFalse(button.intersects(inventory), "the button covers the player inventory" + where);
            }
        }
    }

    @Test
    void theButtonStaysClearOfTheEnergyBar() {
        for (int inputs : INPUT_SLOTS) {
            for (int outputs = 1; outputs <= 64; outputs++) {
                Rect bar = new Rect(
                        SieveLayout.powerBarX(inputs, outputs),
                        SieveLayout.POWER_BAR_Y,
                        12,
                        SieveLayout.POWER_BAR_HEIGHT);
                assertFalse(
                        button().intersects(bar),
                        "the button covers the energy bar at " + inputs + " inputs and " + outputs + " output slots");
            }
        }
    }

    @Test
    void theButtonIsOneSlotLeftOfTheFirstInputSlot() {
        assertTrue(SieveLayout.SPLIT_BUTTON_SIZE > 0, "the button must have a size");
        assertTrue(
                SieveLayout.SPLIT_BUTTON_SIZE <= SieveLayout.SLOT,
                "the button must not be larger than a slot");
        assertTrue(
                SieveLayout.SPLIT_BUTTON_X + SieveLayout.SPLIT_BUTTON_SIZE <= SieveLayout.INPUT_X,
                "the button must end at or before the first input slot");
        assertTrue(SieveLayout.SPLIT_BUTTON_X >= 0, "the button must start inside the window");
    }

    private static Rect button() {
        return new Rect(
                SieveLayout.SPLIT_BUTTON_X,
                SieveLayout.SPLIT_BUTTON_Y,
                SieveLayout.SPLIT_BUTTON_SIZE,
                SieveLayout.SPLIT_BUTTON_SIZE);
    }

    private static Rect input(int index) {
        return new Rect(
                SieveLayout.inputSlotX(index), SieveLayout.inputSlotY(index), SieveLayout.SLOT, SieveLayout.SLOT);
    }

    private static Rect arrow(int index) {
        return new Rect(
                SieveLayout.progressBarX(index),
                SieveLayout.progressBarY(index),
                SieveLayout.PROGRESS_WIDTH,
                SieveLayout.PROGRESS_HEIGHT);
    }

    private static Rect output(int index, int inputSlots, int outputSlots) {
        return new Rect(
                SieveLayout.outputSlotX(index, inputSlots, outputSlots),
                SieveLayout.outputSlotY(index, inputSlots, outputSlots),
                SieveLayout.SLOT,
                SieveLayout.SLOT);
    }

    /** Half-open rectangle, so two rectangles that merely touch at an edge do not overlap. */
    private record Rect(int x, int y, int width, int height) {

        int right() {
            return x + width;
        }

        int bottom() {
            return y + height;
        }

        boolean intersects(Rect other) {
            return x < other.right() && other.x < right() && y < other.bottom() && other.y < bottom();
        }

        @Override
        public String toString() {
            return "[" + x + "," + y + " " + width + "x" + height + "]";
        }
    }
}
