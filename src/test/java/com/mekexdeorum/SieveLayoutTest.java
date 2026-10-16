package com.mekexdeorum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SieveLayout}: the frozen invariants for all five tiers and for the whole
 * configured range of output slot counts.
 */
class SieveLayoutTest {

    /** Input slots of the five tiers: Universal Sieve, Basic, Advanced, Elite, Ultimate factory. */
    private static final int[] INPUT_SLOTS = {1, 3, 5, 7, 9};

    /** Output slots: the configured default, plus both ends of the allowed range. */
    private static final int[] OUTPUT_SLOTS = {1, 16, 64};

    @Test
    void theWindowIsNeverNarrowerThanTheVanillaGui() {
        for (int inputs : INPUT_SLOTS) {
            for (int outputs : OUTPUT_SLOTS) {
                assertTrue(SieveLayout.MIN_WIDTH == 176, "the frozen minimum width is 176");
                assertTrue(
                        SieveLayout.guiWidth(inputs, outputs) >= SieveLayout.MIN_WIDTH,
                        "guiWidth(" + inputs + ", " + outputs + ") is too narrow");
                assertTrue(
                        SieveLayout.guiHeight(inputs, outputs) > SieveLayout.playerInventoryY(inputs, outputs),
                        "the player inventory must fit inside the window");
            }
        }
    }

    @Test
    void everySlotAndProgressBarStaysInsideTheWindow() {
        for (int inputs : INPUT_SLOTS) {
            for (int outputs : OUTPUT_SLOTS) {
                int width = SieveLayout.guiWidth(inputs, outputs);
                int height = SieveLayout.guiHeight(inputs, outputs);

                assertInside("mesh", SieveLayout.MESH_X, SieveLayout.MESH_Y, width, height);
                assertInside("energy", SieveLayout.ENERGY_X, SieveLayout.ENERGY_Y, width, height);

                for (int i = 0; i < inputs; i++) {
                    assertInside("input " + i, SieveLayout.inputSlotX(i), SieveLayout.inputSlotY(i), width, height);
                    assertInside(
                            "progress bar " + i + " left",
                            SieveLayout.progressBarX(i),
                            SieveLayout.progressBarY(i),
                            width,
                            height);
                    assertTrue(
                            SieveLayout.progressBarX(i) + SieveLayout.PROGRESS_WIDTH <= SieveLayout.inputSlotX(i) + SieveLayout.SLOT,
                            "progress bar " + i + " leaves the column of its input slot");
                    assertTrue(
                            SieveLayout.progressBarY(i) + SieveLayout.PROGRESS_HEIGHT <= height,
                            "progress bar " + i + " leaves the window");
                }

                for (int i = 0; i < outputs; i++) {
                    assertInside(
                            "output " + i,
                            SieveLayout.outputSlotX(i, outputs),
                            SieveLayout.outputSlotY(i, outputs),
                            width,
                            height);
                }
            }
        }
    }

    @Test
    void everyProgressBarSitsDirectlyUnderItsInputSlot() {
        for (int inputs : INPUT_SLOTS) {
            for (int i = 0; i < inputs; i++) {
                assertTrue(
                        SieveLayout.progressBarX(i) >= SieveLayout.inputSlotX(i),
                        "progress bar " + i + " starts left of its input slot");
                assertTrue(
                        SieveLayout.progressBarY(i) >= SieveLayout.inputSlotY(i) + SieveLayout.SLOT,
                        "progress bar " + i + " is not below its input slot");
                assertTrue(
                        SieveLayout.progressBarY(i) + SieveLayout.PROGRESS_HEIGHT <= SieveLayout.OUTPUT_Y,
                        "progress bar " + i + " runs into the output grid");
            }
        }
    }

    @Test
    void theOutputGridNeverOverlapsThePlayerInventory() {
        for (int inputs : INPUT_SLOTS) {
            for (int outputs : OUTPUT_SLOTS) {
                int playerInventoryY = SieveLayout.playerInventoryY(inputs, outputs);
                int machineBottom = SieveLayout.machineBottom(outputs);
                int outputsBottom = SieveLayout.OUTPUT_Y + SieveLayout.outputRows(outputs) * SieveLayout.SLOT;

                assertTrue(machineBottom < playerInventoryY, "the slot area must end above the player inventory");
                assertTrue(outputsBottom <= machineBottom, "the output grid defines the bottom of the slot area");
                assertEquals(SieveLayout.OUTPUT_Y, SieveLayout.outputSlotY(0, outputs));
            }
        }
    }

    @Test
    void everySlotOfOneTierHasItsOwnPosition() {
        for (int inputs : INPUT_SLOTS) {
            for (int outputs : OUTPUT_SLOTS) {
                Set<String> positions = new HashSet<>();
                for (int i = 0; i < inputs; i++) {
                    assertTrue(
                            positions.add(SieveLayout.inputSlotX(i) + "," + SieveLayout.inputSlotY(i)),
                            "two input slots share a position at " + inputs + " inputs");
                }
                for (int i = 0; i < outputs; i++) {
                    assertTrue(
                            positions.add(SieveLayout.outputSlotX(i, outputs) + "," + SieveLayout.outputSlotY(i, outputs)),
                            "two output slots share a position at " + outputs + " outputs");
                }
                assertEquals(inputs + outputs, positions.size(), "slots overlap at " + inputs + "/" + outputs);
            }
        }
    }

    @Test
    void theSameInputIndexKeepsItsPositionAcrossTiers() {
        for (int i = 0; i < INPUT_SLOTS[0]; i++) {
            assertEquals(SieveLayout.INPUT_X, SieveLayout.inputSlotX(i));
        }
        for (int inputs : INPUT_SLOTS) {
            for (int i = 0; i < inputs; i++) {
                assertEquals(SieveLayout.INPUT_Y, SieveLayout.inputSlotY(i));
                assertEquals(SieveLayout.INPUT_X + i * SieveLayout.SLOT, SieveLayout.inputSlotX(i));
            }
        }
    }

    @Test
    void theLayoutFunctionsArePure() {
        for (int inputs : INPUT_SLOTS) {
            for (int outputs : OUTPUT_SLOTS) {
                assertEquals(SieveLayout.guiWidth(inputs, outputs), SieveLayout.guiWidth(inputs, outputs));
                assertEquals(SieveLayout.guiHeight(inputs, outputs), SieveLayout.guiHeight(inputs, outputs));
                assertEquals(
                        SieveLayout.playerInventoryY(inputs, outputs), SieveLayout.playerInventoryY(inputs, outputs));
                assertEquals(SieveLayout.powerBarX(inputs, outputs), SieveLayout.powerBarX(inputs, outputs));
                assertEquals(SieveLayout.machineBottom(outputs), SieveLayout.machineBottom(outputs));
                for (int i = 0; i < inputs; i++) {
                    assertEquals(SieveLayout.inputSlotX(i), SieveLayout.inputSlotX(i));
                    assertEquals(SieveLayout.progressBarX(i), SieveLayout.progressBarX(i));
                }
                for (int i = 0; i < outputs; i++) {
                    assertEquals(SieveLayout.outputSlotX(i, outputs), SieveLayout.outputSlotX(i, outputs));
                    assertEquals(SieveLayout.outputSlotY(i, outputs), SieveLayout.outputSlotY(i, outputs));
                }
            }
        }
    }

    @Test
    void theEnergyBarStaysInsideTheWindow() {
        for (int inputs : INPUT_SLOTS) {
            for (int outputs : OUTPUT_SLOTS) {
                int x = SieveLayout.powerBarX(inputs, outputs);
                assertTrue(x >= SieveLayout.OUTPUT_X, "the energy bar must not cover the slots");
                assertTrue(
                        x + 12 <= SieveLayout.guiWidth(inputs, outputs),
                        "the energy bar must fit inside the window");
                assertTrue(
                        SieveLayout.POWER_BAR_Y + SieveLayout.POWER_BAR_HEIGHT <= SieveLayout.guiHeight(inputs, outputs),
                        "the energy bar must fit vertically");
            }
        }
    }

    private static void assertInside(String what, int x, int y, int width, int height) {
        assertTrue(x >= 0 && y >= 0, what + " starts outside the window");
        assertTrue(x + SieveLayout.SLOT <= width, what + " sticks out to the right (" + (x + SieveLayout.SLOT) + " > " + width + ")");
        assertTrue(y + SieveLayout.SLOT <= height, what + " sticks out at the bottom (" + (y + SieveLayout.SLOT) + " > " + height + ")");
    }
}
