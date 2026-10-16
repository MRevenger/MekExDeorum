package com.mekexdeorum;

/**
 * Slot geometry shared by the block entity, the menu and the screen.
 *
 * <p>Coordinates are GUI-relative pixels measured from the top-left corner of the window, so the
 * menu's slots and the screen's overlays cannot drift apart. Only integer pixel arithmetic lives
 * here: no Minecraft and no Mekanism type is imported, so the invariants below are unit-testable.
 *
 * <p>Guaranteed for every supported tier (1/3/5/7/9 input slots, {@code outputSlots} from 1 to 64):
 * <ol>
 *   <li>every slot rectangle and every progress bar lies completely inside
 *       {@code [0, guiWidth) x [0, guiHeight)};</li>
 *   <li>the output grid never overlaps the player inventory, which starts at
 *       {@link #playerInventoryY(int, int)};</li>
 *   <li>{@code guiWidth >= 176}, and every function is pure: the same arguments always give the
 *       same numbers.</li>
 * </ol>
 *
 * <p>Grid: the mesh and the energy slot form a column on the left, the input slots a single row to
 * their right, one progress bar directly under each input slot, and the output grid below that.
 */
public final class SieveLayout {

    /** Edge length of one slot. */
    public static final int SLOT = 18;

    /** Narrowest allowed window, the usual Minecraft GUI width. */
    public static final int MIN_WIDTH = 176;

    /** Slot holding the Ex Deorum mesh. */
    public static final int MESH_X = 26;
    public static final int MESH_Y = 17;

    /** Slot accepting an energy cube or battery. */
    public static final int ENERGY_X = 26;
    public static final int ENERGY_Y = 43;

    /** First input slot; the inputs form a single row. */
    public static final int INPUT_X = 62;
    public static final int INPUT_Y = 17;

    /** Progress bars, one directly under each input slot. */
    public static final int PROGRESS_Y = 38;
    public static final int PROGRESS_INSET = 2;
    public static final int PROGRESS_WIDTH = SLOT - 2 * PROGRESS_INSET;
    public static final int PROGRESS_HEIGHT = 6;

    /** Top-left of the output grid. */
    public static final int OUTPUT_X = 62;
    public static final int OUTPUT_Y = 50;

    /** Output columns while the grid fits in five rows, and beyond that. */
    public static final int OUTPUT_COLUMNS_NARROW = 4;
    public static final int OUTPUT_COLUMNS_WIDE = 6;

    /** At most this many output rows before the grid switches to the wider layout. */
    private static final int MAX_NARROW_ROWS = 5;

    /** Vertical energy bar, matching where Mekanism puts it on its own machines. */
    public static final int POWER_BAR_Y = 16;
    public static final int POWER_BAR_HEIGHT = 52;

    /**
     * Extra room along the right hand edge of the window for the vertical energy bar, so it sits
     * beside the slots instead of on top of them.
     */
    private static final int RIGHT_MARGIN = 16;

    /** Vertical gap between the slot area and the player inventory. */
    private static final int PLAYER_GAP = 8;

    /** Height taken by the three player inventory rows, the gap and the hotbar. */
    private static final int PLAYER_HEIGHT = 82;

    private SieveLayout() {}

    /** X of the input slot with that index. */
    public static int inputSlotX(int index) {
        return INPUT_X + Math.max(0, index) * SLOT;
    }

    /** Y of every input slot, the inputs being a single row. */
    public static int inputSlotY(int index) {
        return INPUT_Y;
    }

    /** X of the progress bar of that input slot. */
    public static int progressBarX(int index) {
        return inputSlotX(index) + PROGRESS_INSET;
    }

    /** Y of every progress bar. */
    public static int progressBarY(int index) {
        return PROGRESS_Y;
    }

    /**
     * How many columns the output grid uses. Wider machines switch to six columns so the window
     * does not get unreasonably tall.
     */
    public static int outputColumns(int outputSlots) {
        int slots = Math.max(1, outputSlots);
        int rows = (slots + OUTPUT_COLUMNS_NARROW - 1) / OUTPUT_COLUMNS_NARROW;
        return rows <= MAX_NARROW_ROWS ? OUTPUT_COLUMNS_NARROW : OUTPUT_COLUMNS_WIDE;
    }

    /** Number of rows the output grid occupies. */
    public static int outputRows(int outputSlots) {
        int slots = Math.max(1, outputSlots);
        return (slots + outputColumns(slots) - 1) / outputColumns(slots);
    }

    /** X of the output slot with that index. */
    public static int outputSlotX(int index, int outputSlots) {
        return OUTPUT_X + Math.max(0, index) % outputColumns(outputSlots) * SLOT;
    }

    /** Y of the output slot with that index. */
    public static int outputSlotY(int index, int outputSlots) {
        return OUTPUT_Y + Math.max(0, index) / outputColumns(outputSlots) * SLOT;
    }

    /** Bottom edge of the slot area, just above the player inventory gap. */
    public static int machineBottom(int outputSlots) {
        int inputsBottom = INPUT_Y + SLOT;
        int progressBottom = PROGRESS_Y + PROGRESS_HEIGHT;
        int energyBottom = ENERGY_Y + SLOT;
        int outputsBottom = OUTPUT_Y + outputRows(outputSlots) * SLOT;
        return Math.max(Math.max(inputsBottom, progressBottom), Math.max(energyBottom, outputsBottom));
    }

    /** Y offset of the player's inventory inside the window; also the menu's inventory offset. */
    public static int playerInventoryY(int inputSlots, int outputSlots) {
        return machineBottom(outputSlots) + PLAYER_GAP;
    }

    /** Window width: wide enough for the input row, the output grid and the energy bar. */
    public static int guiWidth(int inputSlots, int outputSlots) {
        int inputsRight = inputSlotX(inputSlots) + SLOT;
        int outputsRight = OUTPUT_X + outputColumns(outputSlots) * SLOT;
        return Math.max(MIN_WIDTH, Math.max(inputsRight, outputsRight)) + RIGHT_MARGIN;
    }

    /** Window height: slots, gap, player inventory. */
    public static int guiHeight(int inputSlots, int outputSlots) {
        return playerInventoryY(inputSlots, outputSlots) + PLAYER_HEIGHT;
    }

    /** X of the vertical energy bar: hard against the right hand edge of the window. */
    public static int powerBarX(int inputSlots, int outputSlots) {
        return guiWidth(inputSlots, outputSlots) - 12;
    }
}
