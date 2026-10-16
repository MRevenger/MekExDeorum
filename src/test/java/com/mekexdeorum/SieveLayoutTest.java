package com.mekexdeorum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
                            SieveLayout.outputSlotX(i, inputs, outputs),
                            SieveLayout.outputSlotY(i, inputs, outputs),
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
                int machineBottom = SieveLayout.machineBottom(inputs, outputs);
                int outputsBottom = SieveLayout.OUTPUT_Y + SieveLayout.outputRows(inputs, outputs) * SieveLayout.SLOT;

                assertTrue(machineBottom < playerInventoryY, "the slot area must end above the player inventory");
                assertTrue(outputsBottom <= machineBottom, "the output grid defines the bottom of the slot area");
                assertEquals(SieveLayout.OUTPUT_Y, SieveLayout.outputSlotY(0, inputs, outputs));
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
                            positions.add(SieveLayout.outputSlotX(i, inputs, outputs) + ","
                                    + SieveLayout.outputSlotY(i, inputs, outputs)),
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
                assertEquals(
                        SieveLayout.playerInventoryLabelY(inputs, outputs),
                        SieveLayout.playerInventoryLabelY(inputs, outputs));
                assertEquals(SieveLayout.powerBarX(inputs, outputs), SieveLayout.powerBarX(inputs, outputs));
                assertEquals(
                        SieveLayout.machineBottom(inputs, outputs), SieveLayout.machineBottom(inputs, outputs));
                for (int i = 0; i < inputs; i++) {
                    assertEquals(SieveLayout.inputSlotX(i), SieveLayout.inputSlotX(i));
                    assertEquals(SieveLayout.progressBarX(i), SieveLayout.progressBarX(i));
                }
                for (int i = 0; i < outputs; i++) {
                    assertEquals(
                            SieveLayout.outputSlotX(i, inputs, outputs), SieveLayout.outputSlotX(i, inputs, outputs));
                    assertEquals(
                            SieveLayout.outputSlotY(i, inputs, outputs), SieveLayout.outputSlotY(i, inputs, outputs));
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

    // ---------------------------------------------------------------------------------------------
    // P1: the progress arrow is compressed and the output grid moved below it.
    // ---------------------------------------------------------------------------------------------

    @Test
    void theProgressArrowIsCompressedToSixtyFivePercentOfTheInputRow() {
        assertEquals(
                (int) (SieveLayout.INPUT_ROW_HEIGHT * 0.65),
                SieveLayout.PROGRESS_HEIGHT,
                "the arrow must be the compressed height the fix specification prescribes");
        assertTrue(
                SieveLayout.PROGRESS_HEIGHT >= SieveLayout.INPUT_ROW_HEIGHT * 0.6,
                "the arrow is shorter than 60% of the input row: " + SieveLayout.PROGRESS_HEIGHT);
        assertTrue(
                SieveLayout.PROGRESS_HEIGHT <= SieveLayout.INPUT_ROW_HEIGHT * 0.7,
                "the arrow is taller than 70% of the input row: " + SieveLayout.PROGRESS_HEIGHT);
        assertEquals(
                SieveLayout.SLOT - 2 * SieveLayout.PROGRESS_INSET,
                SieveLayout.PROGRESS_WIDTH,
                "the arrow keeps the input slot's inner width");
    }

    @Test
    void theOutputGridStartsOneArrowHeightAndAGapBelowTheInputRow() {
        assertEquals(
                SieveLayout.INPUT_Y + SieveLayout.INPUT_ROW_HEIGHT + SieveLayout.PROGRESS_HEIGHT + SieveLayout.ARROW_GAP,
                SieveLayout.OUTPUT_Y,
                "outputTop = inputAreaBottom + arrowHeight + arrowGap");
        for (int i = 0; i < INPUT_SLOTS[INPUT_SLOTS.length - 1]; i++) {
            assertTrue(
                    SieveLayout.progressBarY(i) >= SieveLayout.inputSlotY(i) + SieveLayout.SLOT,
                    "arrow " + i + " is not below its input slot");
            assertEquals(
                    SieveLayout.OUTPUT_Y - SieveLayout.ARROW_GAP,
                    SieveLayout.progressBarY(i) + SieveLayout.PROGRESS_HEIGHT,
                    "arrow " + i + " does not end one gap above the output grid");
        }
    }

    @Test
    void noProgressArrowTouchesAnOutputSlot() {
        for (int inputs : INPUT_SLOTS) {
            for (int outputs : OUTPUT_SLOTS) {
                for (int i = 0; i < inputs; i++) {
                    Rect bar = arrow(i);
                    for (int slot = 0; slot < outputs; slot++) {
                        assertFalse(
                                bar.intersects(output(slot, inputs, outputs)),
                                "arrow " + i + " " + bar + " overlaps output " + slot + " "
                                        + output(slot, inputs, outputs) + " at " + inputs + " inputs and "
                                        + outputs + " output slots");
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // P2: the output grid is adaptive and centred.
    // ---------------------------------------------------------------------------------------------

    @Test
    void theOutputGridIsCentredInTheAvailableWidth() {
        for (int inputs : INPUT_SLOTS) {
            for (int outputs : OUTPUT_SLOTS) {
                int available = SieveLayout.availableWidth(inputs, outputs);
                int areaWidth = SieveLayout.outputAreaWidth(inputs, outputs);
                int left = SieveLayout.outputAreaLeft(inputs, outputs);
                String where = " at " + inputs + " inputs and " + outputs + " output slots";

                // The centring the fix specification prescribes, verbatim.
                assertEquals(
                        SieveLayout.LEFT_PADDING + (available - areaWidth) / 2,
                        left,
                        "outputAreaLeft = leftPadding + (availableWidth - outputAreaWidth) / 2" + where);
                assertEquals(
                        SieveLayout.guiWidth(inputs, outputs) - SieveLayout.LEFT_PADDING - SieveLayout.RIGHT_MARGIN,
                        available,
                        "availableWidth = guiWidth - leftPadding - rightPadding" + where);
                // The grid may not hang on one side: its two margins may differ by one pixel at most.
                int leftMargin = left - SieveLayout.LEFT_PADDING;
                int rightMargin = SieveLayout.guiWidth(inputs, outputs) - SieveLayout.RIGHT_MARGIN - (left + areaWidth);
                assertTrue(
                        Math.abs(leftMargin - rightMargin) <= 1,
                        "the output grid hangs to one side" + where + ": left margin " + leftMargin
                                + ", right margin " + rightMargin);
                // And the slots really fill that area: the first column starts on its left edge, a
                // full row ends on its right edge, and no slot leaves the area sideways.
                assertEquals(left, SieveLayout.outputSlotX(0, inputs, outputs), "first output column" + where);
                assertEquals(
                        left + areaWidth,
                        SieveLayout.outputSlotX(
                                SieveLayout.outputColumns(inputs, outputs) - 1, inputs, outputs) + SieveLayout.SLOT,
                        "the first output row does not end on the area's right edge" + where);
                for (int slot = 0; slot < outputs; slot++) {
                    Rect rect = output(slot, inputs, outputs);
                    assertTrue(
                            rect.x() >= left && rect.right() <= left + areaWidth,
                            "output " + slot + " " + rect + " leaves the output area" + where);
                }
            }
        }
    }

    @Test
    void theWindowAndTheOutputGridAdaptToTheInputCount() {
        // Section 3.2.2: one input gets the input area plus the margins (the vanilla minimum is the
        // floor), nine inputs widen the window and the output area to the input row.
        assertEquals(176, SieveLayout.guiWidth(1, 16), "a one input machine stays at the minimum width");
        assertEquals(240, SieveLayout.guiWidth(9, 16), "a nine input machine widens to its input row");
        assertEquals(98, SieveLayout.availableWidth(1, 16), "the band of a one input machine");
        assertEquals(162, SieveLayout.availableWidth(9, 16), "the band of a nine input machine");
        assertEquals(5, SieveLayout.outputColumns(1, 16), "the band of a narrow machine holds five slots");
        assertEquals(9, SieveLayout.outputColumns(9, 16), "the output area of the widest tier is the input row");
        assertEquals(4, SieveLayout.outputRows(1, 16), "a narrow band needs more output rows");
        assertEquals(2, SieveLayout.outputRows(9, 16), "the widest band needs fewer");

        for (int outputs : OUTPUT_SLOTS) {
            int previousWidth = 0;
            int previousBand = 0;
            for (int inputs : INPUT_SLOTS) {
                int width = SieveLayout.guiWidth(inputs, outputs);
                int band = SieveLayout.availableWidth(inputs, outputs);
                assertTrue(
                        width >= previousWidth && band >= previousBand,
                        "the window must not shrink when a machine has more inputs, at " + inputs
                                + " inputs and " + outputs + " output slots");
                previousWidth = width;
                previousBand = band;
            }
        }
    }

    @Test
    void theOutputGridUsesTheSlotsPerRowTheSpecificationComputes() {
        for (int inputs : INPUT_SLOTS) {
            for (int outputs = 1; outputs <= 64; outputs++) {
                int perRow = SieveLayout.outputRowCapacity(inputs, outputs);
                String where = " at " + inputs + " inputs and " + outputs + " output slots";
                assertEquals(
                        Math.max(1, SieveLayout.availableWidth(inputs, outputs) / SieveLayout.SLOT_PITCH),
                        perRow,
                        "perRow = max(1, availableWidth / slotPitch)" + where);
                assertEquals(
                        Math.min(perRow, outputs),
                        SieveLayout.outputColumns(inputs, outputs),
                        "perRow does not match the slots the grid really uses" + where);
                assertEquals(
                        SieveLayout.outputColumns(inputs, outputs) * SieveLayout.SLOT_PITCH - SieveLayout.SLOT_SPACING,
                        SieveLayout.outputAreaWidth(inputs, outputs),
                        "outputAreaWidth = min(perRow, outputSlots) * slotPitch - slotSpacing" + where);
                assertEquals(
                        (outputs + perRow - 1) / perRow,
                        SieveLayout.outputRows(inputs, outputs),
                        "rows = ceil(outputSlots / perRow)" + where);
                assertTrue(SieveLayout.outputColumns(inputs, outputs) <= perRow, "the grid is wider than the band" + where);
            }
        }
    }

    @Test
    void theOutputGridClearsThePlayerInventoryAndTheFixedSlots() {
        Rect mesh = new Rect(SieveLayout.MESH_X, SieveLayout.MESH_Y, SieveLayout.SLOT, SieveLayout.SLOT);
        Rect energy = new Rect(SieveLayout.ENERGY_X, SieveLayout.ENERGY_Y, SieveLayout.SLOT, SieveLayout.SLOT);
        for (int inputs : INPUT_SLOTS) {
            for (int outputs : OUTPUT_SLOTS) {
                // Where MekanismContainer puts the player's slots: three rows of nine, then the hotbar.
                Rect inventory = new Rect(8, SieveLayout.playerInventoryY(inputs, outputs), 9 * SieveLayout.SLOT, 76);
                for (int slot = 0; slot < outputs; slot++) {
                    Rect rect = output(slot, inputs, outputs);
                    String where = " at " + inputs + " inputs and " + outputs + " output slots";
                    assertFalse(rect.intersects(inventory), "output " + slot + " overlaps the player inventory" + where);
                    assertFalse(rect.intersects(mesh), "output " + slot + " overlaps the mesh slot" + where);
                    assertFalse(rect.intersects(energy), "output " + slot + " overlaps the energy slot" + where);
                    for (int other = slot + 1; other < outputs; other++) {
                        assertFalse(
                                rect.intersects(output(other, inputs, outputs)),
                                "output " + slot + " overlaps output " + other + where);
                    }
                }
            }
        }
    }

    @Test
    void everySlotAndArrowStaysInsideTheWindowForEveryConfiguredOutputCount() {
        for (int inputs : INPUT_SLOTS) {
            for (int outputs = 1; outputs <= 64; outputs++) {
                int width = SieveLayout.guiWidth(inputs, outputs);
                int height = SieveLayout.guiHeight(inputs, outputs);
                assertInside("mesh", SieveLayout.MESH_X, SieveLayout.MESH_Y, width, height);
                assertInside("energy", SieveLayout.ENERGY_X, SieveLayout.ENERGY_Y, width, height);
                for (int i = 0; i < inputs; i++) {
                    assertInside("input " + i, SieveLayout.inputSlotX(i), SieveLayout.inputSlotY(i), width, height);
                    assertRectInside(arrow(i), width, height, "arrow " + i);
                }
                for (int slot = 0; slot < outputs; slot++) {
                    assertRectInside(output(slot, inputs, outputs), width, height, "output " + slot);
                }
            }
        }
    }

    @Test
    void theWindowNeverExceedsMinecraftsGuaranteedGuiSize() {
        assertEquals(240, SieveLayout.MAX_HEIGHT, "the frozen height bound is 240");
        assertEquals(320, SieveLayout.MAX_WIDTH, "the frozen width bound is 320");
        // The player block below the machine and the gap above it, read back from the public API so
        // that the expectation below cannot be typed in twice.
        int playerGap = SieveLayout.playerInventoryY(1, 1) - SieveLayout.machineBottom(1, 1);
        int playerBlock = SieveLayout.guiHeight(1, 1) - SieveLayout.playerInventoryY(1, 1);
        assertEquals(
                Math.max(1, (SieveLayout.MAX_HEIGHT - SieveLayout.OUTPUT_Y - playerGap - playerBlock) / SieveLayout.SLOT),
                SieveLayout.MAX_OUTPUT_ROWS,
                "the row budget must be derived from the height bound");
        int tallest = 0;
        int widest = 0;
        int narrowest = Integer.MAX_VALUE;
        int shortest = Integer.MAX_VALUE;
        for (int inputs : INPUT_SLOTS) {
            for (int outputs = 1; outputs <= 64; outputs++) {
                int height = SieveLayout.guiHeight(inputs, outputs);
                int width = SieveLayout.guiWidth(inputs, outputs);
                tallest = Math.max(tallest, height);
                widest = Math.max(widest, width);
                shortest = Math.min(shortest, height);
                narrowest = Math.min(narrowest, width);
                assertTrue(
                        height <= SieveLayout.MAX_HEIGHT,
                        "guiHeight(" + inputs + ", " + outputs + ") = " + height + " exceeds " + SieveLayout.MAX_HEIGHT);
                assertTrue(
                        width <= SieveLayout.MAX_WIDTH,
                        "guiWidth(" + inputs + ", " + outputs + ") = " + width + " exceeds " + SieveLayout.MAX_WIDTH);
                assertTrue(
                        SieveLayout.outputRows(inputs, outputs) <= SieveLayout.MAX_OUTPUT_ROWS,
                        SieveLayout.outputRows(inputs, outputs) + " output rows at " + outputs + " output slots");
            }
        }
        System.out.println("gui size over every tier and every output count 1..64: width "
                + narrowest + ".." + widest + ", height " + shortest + ".." + tallest
                + " (bounds " + SieveLayout.MIN_WIDTH + ".." + SieveLayout.MAX_WIDTH + " x " + SieveLayout.MAX_HEIGHT + ")");
    }

    @Test
    void thePlayerInventoryLabelFitsBetweenTheMachineAndTheInventory() {
        for (int inputs : INPUT_SLOTS) {
            for (int outputs = 1; outputs <= 64; outputs++) {
                int labelY = SieveLayout.playerInventoryLabelY(inputs, outputs);
                int inventoryY = SieveLayout.playerInventoryY(inputs, outputs);
                int machineBottom = SieveLayout.machineBottom(inputs, outputs);
                String where = " at " + inputs + " inputs and " + outputs + " output slots";

                // The label box ends where the first inventory row begins, so it cannot paint over it.
                assertTrue(
                        labelY + SieveLayout.LABEL_HEIGHT <= inventoryY,
                        "the label covers the first inventory row" + where);
                // The free band is PLAYER_GAP tall and the box is LABEL_HEIGHT tall, one pixel more,
                // so the only pixel above the band is the box's blank top row. No slot geometric
                // function reads the label, so nothing else can move because of it.
                assertTrue(
                        machineBottom - labelY <= 1,
                        "the label climbs into the machine's slot area" + where);
                assertTrue(
                        labelY >= SieveLayout.ENERGY_Y + SieveLayout.SLOT,
                        "the label covers the mesh and energy column" + where);
                assertTrue(labelY > 0, "the label starts outside the window" + where);
                assertTrue(
                        labelY + SieveLayout.LABEL_HEIGHT <= SieveLayout.guiHeight(inputs, outputs),
                        "the label leaves the window" + where);
            }
        }
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

    private static void assertRectInside(Rect rect, int width, int height, String what) {
        assertTrue(rect.x() >= 0 && rect.y() >= 0, what + " starts outside the window");
        assertTrue(rect.right() <= width, what + " sticks out to the right (" + rect.right() + " > " + width + ")");
        assertTrue(rect.bottom() <= height, what + " sticks out at the bottom (" + rect.bottom() + " > " + height + ")");
    }

    /** Half-open rectangle, so two slots that merely touch at an edge do not count as overlapping. */
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

    private static void assertInside(String what, int x, int y, int width, int height) {
        assertTrue(x >= 0 && y >= 0, what + " starts outside the window");
        assertTrue(x + SieveLayout.SLOT <= width, what + " sticks out to the right (" + (x + SieveLayout.SLOT) + " > " + width + ")");
        assertTrue(y + SieveLayout.SLOT <= height, what + " sticks out at the bottom (" + (y + SieveLayout.SLOT) + " > " + height + ")");
    }
}
