package com.mekexdeorum;

/**
 * Slot geometry shared by the block entity, the menu and the screen.
 *
 * <p>Coordinates are GUI-relative pixels measured from the top-left corner of the window, so the
 * menu's slots and the screen's overlays cannot drift apart. Only integer pixel arithmetic lives
 * here: no Minecraft and no Mekanism type is imported, so the invariants below are unit-testable.
 *
 * <p>Top to bottom the window holds the mesh and energy column on the left, the input row, one
 * progress arrow per input slot, the output grid and the player inventory. The output grid is
 * centred in the band between {@link #LEFT_PADDING} and {@link #RIGHT_MARGIN}, and the band is as
 * wide as the wider of two things: the input row, and the columns the output grid needs to stay
 * within {@link #MAX_OUTPUT_ROWS} rows. A machine with one input therefore gets a narrow window
 * (its input area plus the margins, never below {@link #MIN_WIDTH}), a machine with nine inputs gets
 * an output area as wide as its input row, and an output count that does not fit in one row becomes
 * several rows of slots that fill the band.
 *
 * <p>Every geometry function takes both slot counts, and the block entity, the menu and the screen
 * all call the same ones with the machine's input count and the configured output count, so the two
 * sides place every slot identically.
 *
 * <p>Guaranteed for every supported tier (1/3/5/7/9 input slots, {@code outputSlots} between 1 and
 * 64):
 * <ol>
 *   <li>no progress arrow intersects any output slot;</li>
 *   <li>every slot rectangle and every progress arrow lies completely inside
 *       {@code [0, guiWidth) x [0, guiHeight)};</li>
 *   <li>the output grid overlaps neither the player inventory (which starts at
 *       {@link #playerInventoryY(int, int)}) nor the mesh and energy slots;</li>
 *   <li>the output grid is centred in the band: its left and right margins differ by at most one
 *       pixel;</li>
 *   <li>the window fits the logical GUI size Minecraft guarantees, {@code 320x240}: the game only
 *       raises the GUI scale while dividing the frame buffer by the next scale still leaves that
 *       much room, so a window inside those bounds is fully visible at every scale a player can
 *       select;</li>
 *   <li>the player inventory label has a row of its own above the inventory, so it covers neither
 *       the inventory's first row nor the machine's last slot row;</li>
 *   <li>{@code guiWidth >= }{@link #MIN_WIDTH}, and every function is pure: the same arguments
 *       always give the same numbers.</li>
 * </ol>
 */
public final class SieveLayout {

    /** Edge length of one slot. */
    public static final int SLOT = 18;

    /** Gap between two neighbouring slots of a row; the slots of a row are adjacent. */
    public static final int SLOT_SPACING = 0;

    /** Distance from the left edge of one slot to the left edge of the next one. */
    public static final int SLOT_PITCH = SLOT + SLOT_SPACING;

    /** Narrowest allowed window, the usual Minecraft GUI width. */
    public static final int MIN_WIDTH = 176;

    /** Widest allowed window, the width half of the guaranteed logical GUI size. */
    public static final int MAX_WIDTH = 320;

    /** Tallest allowed window, the height half of the guaranteed logical GUI size. */
    public static final int MAX_HEIGHT = 240;

    /** Slot holding the Ex Deorum mesh. */
    public static final int MESH_X = 26;
    public static final int MESH_Y = 17;

    /** Slot accepting an energy cube or battery. */
    public static final int ENERGY_X = 26;
    public static final int ENERGY_Y = 43;

    /** First input slot; the inputs form a single row. */
    public static final int INPUT_X = 62;
    public static final int INPUT_Y = 17;

    /** Left padding of the slot area: the mesh and energy column, plus a slot wide gap. */
    public static final int LEFT_PADDING = INPUT_X;

    /** Right padding of the slot area: room for the vertical energy bar along the window's edge. */
    public static final int RIGHT_MARGIN = 16;

    /** Height of one input row: the input slot itself. */
    public static final int INPUT_ROW_HEIGHT = SLOT;

    /**
     * Progress arrows: the input slot's inner width, and 65% of the input row's height. Keeping the
     * width and compressing the height is what frees the output grid underneath.
     */
    public static final int PROGRESS_INSET = 2;
    public static final int PROGRESS_WIDTH = SLOT - 2 * PROGRESS_INSET;
    public static final int ARROW_HEIGHT = (int) (INPUT_ROW_HEIGHT * 0.65);

    /** Gap between the bottom of the arrows and the top of the output grid. */
    public static final int ARROW_GAP = 4;

    /** Top of every progress arrow: directly under the input row. */
    public static final int PROGRESS_Y = INPUT_Y + INPUT_ROW_HEIGHT;

    /** Height of a progress arrow. */
    public static final int PROGRESS_HEIGHT = ARROW_HEIGHT;

    /** Left edge of the output band; the grid itself starts at {@link #outputAreaLeft(int, int)}. */
    public static final int OUTPUT_X = LEFT_PADDING;

    /** Top of the output grid: one arrow height plus the gap below the input area. */
    public static final int OUTPUT_Y = PROGRESS_Y + ARROW_HEIGHT + ARROW_GAP;

    /** Vertical gap between the slot area and the player inventory. */
    private static final int PLAYER_GAP = 8;

    /** Height taken by the three player inventory rows, the gap and the hotbar. */
    private static final int PLAYER_HEIGHT = 82;

    /** Height of the player inventory label's text box: Minecraft's font line height. */
    public static final int LABEL_HEIGHT = 9;

    /**
     * Most output rows a window may use and still respect {@link #MAX_HEIGHT}:
     * {@code OUTPUT_Y + rows * SLOT + PLAYER_GAP + PLAYER_HEIGHT <= MAX_HEIGHT}.
     */
    public static final int MAX_OUTPUT_ROWS =
            Math.max(1, (MAX_HEIGHT - OUTPUT_Y - PLAYER_GAP - PLAYER_HEIGHT) / SLOT);

    /** Width of the vertical energy bar along the window's right hand edge. */
    private static final int POWER_BAR_WIDTH = 12;

    /** Vertical energy bar, matching where Mekanism puts it on its own machines. */
    public static final int POWER_BAR_Y = 16;
    public static final int POWER_BAR_HEIGHT = 52;

    private SieveLayout() {}

    /** X of the input slot with that index. */
    public static int inputSlotX(int index) {
        return INPUT_X + Math.max(0, index) * SLOT;
    }

    /** Y of every input slot, the inputs being a single row. */
    public static int inputSlotY(int index) {
        return INPUT_Y;
    }

    /** X of the progress arrow of that input slot. */
    public static int progressBarX(int index) {
        return inputSlotX(index) + PROGRESS_INSET;
    }

    /** Y of every progress arrow. */
    public static int progressBarY(int index) {
        return PROGRESS_Y;
    }

    /**
     * Output slots of one row: {@code min(perRow, outputSlots)}, the slots that fit in the band
     * without using more than {@link #MAX_OUTPUT_ROWS} rows.
     */
    public static int outputColumns(int inputSlots, int outputSlots) {
        return Math.min(outputRowCapacity(inputSlots, outputSlots), Math.max(1, outputSlots));
    }

    /** Number of rows the output grid occupies, {@code ceil(outputSlots / perRow)}. */
    public static int outputRows(int inputSlots, int outputSlots) {
        int columns = outputColumns(inputSlots, outputSlots);
        return (Math.max(1, outputSlots) + columns - 1) / columns;
    }

    /** Horizontal band the machine's slots live in: the window minus its two paddings. */
    public static int availableWidth(int inputSlots, int outputSlots) {
        return guiWidth(inputSlots, outputSlots) - LEFT_PADDING - RIGHT_MARGIN;
    }

    /** Output slots that fit in one row: {@code availableWidth / slotPitch}. */
    public static int outputRowCapacity(int inputSlots, int outputSlots) {
        return Math.max(1, availableWidth(inputSlots, outputSlots) / SLOT_PITCH);
    }

    /** Width of the output grid: {@code min(perRow, outputSlots) * slotPitch - slotSpacing}. */
    public static int outputAreaWidth(int inputSlots, int outputSlots) {
        int perRow = outputRowCapacity(inputSlots, outputSlots);
        return Math.min(perRow, Math.max(1, outputSlots)) * SLOT_PITCH - SLOT_SPACING;
    }

    /**
     * Left edge of the output grid: the grid is centred in the band, so the space left of it equals
     * the space right of it.
     */
    public static int outputAreaLeft(int inputSlots, int outputSlots) {
        int available = availableWidth(inputSlots, outputSlots);
        return LEFT_PADDING + (available - outputAreaWidth(inputSlots, outputSlots)) / 2;
    }

    /** X of the output slot with that index. */
    public static int outputSlotX(int index, int inputSlots, int outputSlots) {
        return outputAreaLeft(inputSlots, outputSlots)
                + Math.max(0, index) % outputColumns(inputSlots, outputSlots) * SLOT;
    }

    /** Y of the output slot with that index. */
    public static int outputSlotY(int index, int inputSlots, int outputSlots) {
        return OUTPUT_Y + Math.max(0, index) / outputColumns(inputSlots, outputSlots) * SLOT;
    }

    /** Fewest output columns that keep the grid within {@link #MAX_OUTPUT_ROWS} rows. */
    private static int requiredColumns(int outputSlots) {
        return (Math.max(1, outputSlots) + MAX_OUTPUT_ROWS - 1) / MAX_OUTPUT_ROWS;
    }

    /** Bottom edge of the slot area, just above the player inventory gap. */
    public static int machineBottom(int inputSlots, int outputSlots) {
        int inputsBottom = INPUT_Y + SLOT;
        int arrowBottom = PROGRESS_Y + PROGRESS_HEIGHT;
        int energyBottom = ENERGY_Y + SLOT;
        int outputsBottom = OUTPUT_Y + outputRows(inputSlots, outputSlots) * SLOT;
        return Math.max(Math.max(inputsBottom, arrowBottom), Math.max(energyBottom, outputsBottom));
    }

    /** Y offset of the player's inventory inside the window; also the menu's inventory offset. */
    public static int playerInventoryY(int inputSlots, int outputSlots) {
        return machineBottom(inputSlots, outputSlots) + PLAYER_GAP;
    }

    /**
     * Y of the player inventory label. The label fills the gap above the inventory with the bottom
     * of its text box flush with the first inventory row: the gap is {@link #PLAYER_GAP} pixels and
     * the box is {@link #LABEL_HEIGHT} tall, so the single pixel that does not fit is the box's
     * blank top row, which rests on the machine's last slot pixel row.
     */
    public static int playerInventoryLabelY(int inputSlots, int outputSlots) {
        return playerInventoryY(inputSlots, outputSlots) - LABEL_HEIGHT;
    }

    /**
     * Window width: wide enough for the input row and for the output grid, the wider of the two
     * being what the band holds.
     */
    public static int guiWidth(int inputSlots, int outputSlots) {
        int bandColumns = Math.max(Math.max(1, inputSlots), requiredColumns(outputSlots));
        return Math.max(MIN_WIDTH, INPUT_X + bandColumns * SLOT + RIGHT_MARGIN);
    }

    /** Window height: slots, gap, player inventory. */
    public static int guiHeight(int inputSlots, int outputSlots) {
        return playerInventoryY(inputSlots, outputSlots) + PLAYER_HEIGHT;
    }

    /** X of the vertical energy bar: hard against the right hand edge of the window. */
    public static int powerBarX(int inputSlots, int outputSlots) {
        return guiWidth(inputSlots, outputSlots) - POWER_BAR_WIDTH;
    }
}
