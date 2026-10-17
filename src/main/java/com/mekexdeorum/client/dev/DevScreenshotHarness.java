package com.mekexdeorum.client.dev;

import com.mekexdeorum.MekExDeorum;
import com.mekexdeorum.SieveLayout;
import com.mekexdeorum.client.gui.GuiSieve;
import com.mekexdeorum.inventory.container.SieveContainer;
import com.mekexdeorum.registry.MekExDeorumBlocks;
import com.mekexdeorum.tile.TileEntitySieve;
import com.mojang.logging.LogUtils;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import mekanism.api.RelativeSide;
import mekanism.client.gui.element.button.ToggleButton;
import mekanism.client.gui.element.tab.window.GuiSideConfigurationTab;
import mekanism.client.gui.element.window.GuiWindow;
import mekanism.common.lib.transmitter.TransmissionType;
import mekanism.common.registration.impl.BlockRegistryObject;
import mekanism.common.tile.component.config.ConfigInfo;
import mekanism.common.tile.component.config.DataType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import org.slf4j.Logger;

/**
 * Dev-only screenshot harness for the F4 verification workstream.
 *
 * <p>It is inert unless <em>all three</em> of the following hold: the client is a non-production
 * (development) runtime, the JVM system property {@code -Dmekexdeorum.screenshots=true} is set, and
 * the marker file {@code screenshots-enabled.flag} exists in the run directory. When any one of them
 * is missing nothing at all happens: no world is opened, no block is touched, no screen is captured.
 *
 * <p>What it does, once enabled: opens the flat verification world and captures genuine client renders
 * through Minecraft's own {@link Screenshot}. It runs the <em>tier-row</em> phase first: the five
 * machines are placed adjacent in a row (x = 20..24, z = {@link #ROW_Z}) and photographed front-on,
 * diagonally, and once per tier up close, so that the tier-coloured bar on each factory front is
 * comparable at a single viewing angle and a single lighting condition; the row is then cleared again
 * so the later phases see exactly the world they saw before. Then the original phases run unchanged:
 * the five machines on the fixed grid at z = 0, eight viewing angles per tier, and one GUI shot per
 * tier. Shots land in
 * {@code <run>/screenshots/} together with {@code harness.log}, a line-by-line record of what was
 * done and of the layout numbers the screens were built from.
 *
 * <p>It never changes machine behaviour: the only gameplay state it touches is the placement of the
 * five blocks it photographs, the player's own position/mode, and the time of day.
 *
 * <p>To remove it: delete this file (and the {@code client/dev} package if nothing else is in it),
 * or simply delete {@code <run>/screenshots-enabled.flag} to disable it for a run.
 */
@OnlyIn(Dist.CLIENT)
@EventBusSubscriber(modid = MekExDeorum.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class DevScreenshotHarness {

    private static final Logger LOG = LogUtils.getLogger();

    private static final String PROPERTY = "mekexdeorum.screenshots";
    private static final String MARKER_FILE = "screenshots-enabled.flag";
    private static final String WORLD = "f4verif";

    /** Height the photographed machines are placed at: high enough that the bottom face is visible. */
    private static final int MACHINE_Y = -54;
    private static final int[] MACHINE_X = {20, 25, 30, 35, 40};

    /**
     * The tier row: the five machines adjacent in x (so they touch) at the same y as the grid. The z is
     * positive on purpose, because the front-on camera looks towards +Z: with z = {@code 30} the grid at
     * z = 0 is behind the camera and cannot appear in the row shots' background.
     */
    private static final int ROW_START_X = 20;
    private static final int ROW_Z = 30;
    /** Fraction of the window width the 5-block row is framed to fill: front-on, closer, diagonal. */
    private static final double ROW_FRONT_FRACTION = 0.75;
    private static final double ROW_CLOSE_FRACTION = 0.90;
    private static final double ROW_DIAGONAL_FRACTION = 0.80;
    /** Distance from the front face for the per-tier close-ups. */
    private static final double TIER_CLOSE_DISTANCE = 1.15;
    /** The bar covers the top 2 of 16 model pixels of the front face, i.e. its centre sits here. */
    private static final double BAR_CENTRE_Y = MACHINE_Y + 1.0 - 1.0 / 16.0;

    private static final String[] ROW_SHOT_NAMES = {
            "row_tiers_front.png", "row_tiers_front_close.png", "row_tiers_diagonal.png",
            "bar_universal.png", "bar_basic.png", "bar_advanced.png", "bar_elite.png", "bar_ultimate.png"};

    /** Camera offsets from the block centre, one per viewing angle. */
    private static final double[][] ANGLES = {
            {0.0, 0.0, -4.0},   // front  (the block's default facing is north, -Z)
            {0.0, 0.0, 4.0},    // back
            {-4.0, 0.0, 0.0},   // left
            {4.0, 0.0, 0.0},    // right
            {0.0, 4.0, 0.0},    // top
            {0.0, -4.0, 0.0},   // bottom
            {-2.83, 0.0, -2.83},
            {2.83, 0.0, -2.83}};
    private static final String[] ANGLE_NAMES = {
            "front", "back", "left", "right", "top", "bottom", "diagonal_left", "diagonal_right"};
    private static final String[] TIER_NAMES = {
            "universal_sieve", "basic_sieve_factory", "advanced_sieve_factory",
            "elite_sieve_factory", "ultimate_sieve_factory"};

    /** Phase order: the tier row is captured first, then the original grid / GUI / probe phases. */
    private static final int PHASE_WORLD = 0;
    private static final int PHASE_ROW_SETUP = 1;
    private static final int PHASE_ROW_SHOTS = 2;
    private static final int PHASE_GRID_SETUP = 3;
    private static final int PHASE_MODEL_SHOTS = 4;
    private static final int PHASE_GUI_SHOTS = 5;
    private static final int PHASE_PROBE = 6;
    private static final int PHASE_EJECT = 7;
    private static final int PHASE_EJECT_GATE = 8;
    private static final int PHASE_SPLIT = 9;

    private static final int WAIT_WORLD = 60;
    private static final int WAIT_SETTLE = 80;
    private static final int WAIT_ROW_SETTLE = 100;
    private static final int WAIT_SHOT = 4;
    private static final int WAIT_ROW_SHOT = 8;
    private static final int WAIT_GUI = 8;
    private static final int HARD_TICK_LIMIT = 8000;

    private static Boolean enabled;
    private static int ticks;
    private static int phase;
    private static int step;
    private static int nextTick;
    private static boolean worldRequested;
    private static boolean armed;
    private static int shots;
    private static boolean guiFallbackUsed;

    private DevScreenshotHarness() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (!enabled()) {
            return;
        }
        ticks++;
        try {
            if (ticks > HARD_TICK_LIMIT && phase < PHASE_PROBE) {
                log(mc, "HARD TICK LIMIT reached in phase " + phase + " step " + step + "; stopping");
                phase = PHASE_PROBE;
            }
            switch (phase) {
                case PHASE_WORLD -> waitForWorld(mc);
                case PHASE_ROW_SETUP -> rowSetup(mc);
                case PHASE_ROW_SHOTS -> rowShots(mc);
                case PHASE_GRID_SETUP -> gridSetup(mc);
                case PHASE_MODEL_SHOTS -> modelShots(mc);
                case PHASE_GUI_SHOTS -> guiShots(mc);
                case PHASE_PROBE -> functionalProbe(mc);
                case PHASE_EJECT -> ejectionProbe(mc);
                case PHASE_EJECT_GATE -> ejectGateProbe(mc);
                case PHASE_SPLIT -> splitProbe(mc);
                default -> {
                    // Finished: nothing further is done, the game is left running for collection.
                }
            }
        } catch (Throwable throwable) {
            logThrowable(mc, "EXCEPTION in phase " + phase + " step " + step, throwable);
            phase = PHASE_PROBE;
        }
    }

    /** Both gates for "may this run touch the game at all". */
    private static boolean enabled() {
        if (enabled == null) {
            boolean property = Boolean.parseBoolean(System.getProperty(PROPERTY, "false"));
            boolean production = net.neoforged.fml.loading.FMLEnvironment.production;
            boolean marker = new File(MARKER_FILE).isFile();
            enabled = !production && property && marker;
            LOG.info("[F4 harness] gate: production={} property {}={} markerFile {}={} -> enabled={}",
                    production, PROPERTY, property, MARKER_FILE, marker, enabled);
        }
        return enabled;
    }

    // ------------------------------------------------------------------
    // Phase 0: get into a world
    // ------------------------------------------------------------------

    private static void waitForWorld(Minecraft mc) {
        if (mc.level != null && mc.player != null) {
            if (step == 0) {
                step = 1;
                nextTick = ticks + WAIT_WORLD;
                log(mc, "world loaded (" + mc.level.dimension().location() + "), waiting " + WAIT_WORLD
                        + " ticks for chunks and the loading screen");
            } else if (step == 1 && ticks >= nextTick && mc.screen == null) {
                log(mc, "screen is clear; starting the tier-row setup");
                phase = PHASE_ROW_SETUP;
                step = 0;
            }
            return;
        }
        if (!worldRequested && ticks >= 40) {
            worldRequested = true;
            log(mc, "requesting world '" + WORLD + "' through WorldOpenFlows.openWorld");
            try {
                mc.createWorldOpenFlows().openWorld(WORLD,
                        () -> log(mc, "openWorld onFail: world '" + WORLD + "' could not be opened"));
            } catch (Throwable throwable) {
                logThrowable(mc, "openWorld threw", throwable);
            }
        }
    }

    // ------------------------------------------------------------------
    // Phase 1: place the five machines adjacent in a row and verify them
    // ------------------------------------------------------------------

    private static BlockPos rowPos(int tier) {
        return new BlockPos(ROW_START_X + tier, MACHINE_Y, ROW_Z);
    }

    private static void rowSetup(Minecraft mc) {
        if (step == 0) {
            mc.options.hideGui = true;
            mc.options.bobView().set(false);
            mc.options.smoothCamera = false;
            log(mc, "window " + mc.getWindow().getWidth() + "x" + mc.getWindow().getHeight()
                    + " guiScale=" + mc.getWindow().getGuiScale() + " (logical "
                    + mc.getWindow().getGuiScaledWidth() + "x" + mc.getWindow().getGuiScaledHeight() + ")");
            logLayout();
            serverAction(mc, sp -> {
                ServerLevel level = (ServerLevel) sp.level();
                level.setDayTime(6000L);
                level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DAYLIGHT).set(false, sp.server);
                level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_WEATHER_CYCLE).set(false, sp.server);
                sp.setGameMode(GameType.CREATIVE);
                sp.getAbilities().mayfly = true;
                sp.getAbilities().flying = true;
                sp.onUpdateAbilities();
                placeRow(mc, level);
                verifyRow(mc, level);
            });
            // Stand in front of the middle of the row, so the whole row's chunks are loaded before shooting.
            placePlayer(mc, ROW_START_X + 2.5, MACHINE_Y + 0.5, ROW_Z - 6.5, 0.0F, 0.0F);
            step = 1;
            nextTick = ticks + WAIT_ROW_SETTLE;
        } else if (step == 1 && ticks >= nextTick) {
            log(mc, "row settled at " + fmt(mc.player.getX()) + " " + fmt(mc.player.getY()) + " "
                    + fmt(mc.player.getZ()) + "; starting the tier-row shots");
            phase = PHASE_ROW_SHOTS;
            step = 0;
            armed = false;
        }
    }

    /** Places the five machines touching each other, and records what each position held beforehand. */
    private static void placeRow(Minecraft mc, ServerLevel level) {
        List<BlockRegistryObject<?, ?>> machines = MekExDeorumBlocks.machines();
        for (int tier = 0; tier < MACHINE_X.length; tier++) {
            BlockPos pos = rowPos(tier);
            BlockState before = level.getBlockState(pos);
            boolean placed = level.setBlock(pos, machines.get(tier).defaultState(), 3);
            log(mc, "row setblock " + pos.toShortString() + " " + TIER_NAMES[tier] + " (position held "
                    + before.getBlock().getName().getString() + ") -> setBlock=" + placed);
        }
    }

    /**
     * Reads every row position back out of the level before anything is photographed: the block state
     * must be the expected machine, the block entity must exist, and the position above it must be lit
     * (sky and block light), otherwise the bars would be photographed in shadow.
     */
    private static void verifyRow(Minecraft mc, ServerLevel level) {
        List<BlockRegistryObject<?, ?>> machines = MekExDeorumBlocks.machines();
        StringBuilder builder = new StringBuilder("row verification:");
        boolean allMachines = true;
        boolean allLit = true;
        for (int tier = 0; tier < MACHINE_X.length; tier++) {
            BlockPos pos = rowPos(tier);
            BlockPos above = pos.above();
            BlockState state = level.getBlockState(pos);
            boolean isMachine = state.is(machines.get(tier).defaultState().getBlock());
            int skyLight = level.getBrightness(LightLayer.SKY, above);
            int blockLight = level.getBrightness(LightLayer.BLOCK, above);
            allMachines &= isMachine;
            allLit &= (skyLight >= 14 || blockLight >= 14);
            builder.append("\n  x=").append(pos.getX())
                    .append(" expected=").append(TIER_NAMES[tier])
                    .append(" actual=").append(state.getBlock().getName().getString())
                    .append(" stateMatch=").append(isMachine)
                    .append(" blockEntity=").append(level.getBlockEntity(pos) == null
                            ? "null" : level.getBlockEntity(pos).getClass().getSimpleName())
                    .append(" skyLightAbove=").append(skyLight)
                    .append(" blockLightAbove=").append(blockLight);
        }
        builder.append("\n  all five positions hold their machine: ").append(allMachines)
                .append("; all five are lit: ").append(allLit);
        log(mc, builder.toString());
        if (!allMachines || !allLit) {
            LOG.warn("[F4 harness] ROW VERIFICATION FAILED (machines={}, lit={}); the row shots would not "
                    + "show five adjacent machines", allMachines, allLit);
        }
    }

    private static void clearRow(Minecraft mc, ServerLevel level) {
        for (int tier = 0; tier < MACHINE_X.length; tier++) {
            BlockPos pos = rowPos(tier);
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            log(mc, "row cleared " + pos.toShortString() + ", now "
                    + level.getBlockState(pos).getBlock().getName().getString());
        }
    }

    // ------------------------------------------------------------------
    // Phase 2: the tier row (front-on, closer, diagonal, one close-up per tier)
    // ------------------------------------------------------------------

    private static void rowShots(Minecraft mc) {
        if (step >= ROW_SHOT_NAMES.length) {
            log(mc, "tier-row shots done (" + shots + " screenshots so far); clearing the row again "
                    + "so the grid phases see the world unchanged");
            serverAction(mc, sp -> clearRow(mc, (ServerLevel) sp.level()));
            phase = PHASE_GRID_SETUP;
            step = 0;
            armed = false;
            return;
        }
        if (!armed) {
            aimRowShot(mc, step);
            armed = true;
            nextTick = ticks + WAIT_ROW_SHOT;
        } else if (ticks >= nextTick) {
            grab(mc, ROW_SHOT_NAMES[step]);
            armed = false;
            step++;
            nextTick = ticks + 2;
        }
    }

    private static void aimRowShot(Minecraft mc, int index) {
        double centreX = ROW_START_X + MACHINE_X.length / 2.0;
        switch (index) {
            case 0, 1 -> {
                double distance = distanceForWidth(mc, MACHINE_X.length,
                        index == 0 ? ROW_FRONT_FRACTION : ROW_CLOSE_FRACTION);
                log(mc, (index == 0 ? "front-on" : "front-on close") + " framing: fov=" + fovDegrees(mc)
                        + " aspect=" + fmt(aspect(mc)) + " distance=" + fmt(distance)
                        + " (5 blocks fill " + fmt(MACHINE_X.length / (2.0 * distance * halfWidthTan(mc)))
                        + " of the window width)");
                placePlayerLookingAt(mc, centreX, MACHINE_Y + 1.35, ROW_Z - distance,
                        centreX, MACHINE_Y + 0.9, ROW_Z);
            }
            case 2 -> {
                double distance = distanceForWidth(mc, MACHINE_X.length, ROW_DIAGONAL_FRACTION);
                double dx = 0.45 * distance;
                double dz = 0.89 * distance;
                log(mc, "diagonal framing: fov=" + fovDegrees(mc) + " aspect=" + fmt(aspect(mc))
                        + " distance=" + fmt(Math.sqrt(dx * dx + dz * dz)));
                placePlayerLookingAt(mc, centreX - dx, MACHINE_Y + 1.9, ROW_Z + 0.5 - dz,
                        centreX, MACHINE_Y + 0.85, ROW_Z);
            }
            default -> {
                int tier = index - 3;
                double x = ROW_START_X + tier + 0.5;
                log(mc, "close-up of " + TIER_NAMES[tier] + " front: distance=" + TIER_CLOSE_DISTANCE
                        + " aimed at the bar line y=" + fmt(BAR_CENTRE_Y));
                placePlayerLookingAt(mc, x, BAR_CENTRE_Y, ROW_Z - TIER_CLOSE_DISTANCE,
                        x, BAR_CENTRE_Y, ROW_Z);
            }
        }
    }

    /**
     * Distance at which a world-space width fills the given fraction of the window width. It is derived
     * from the live FOV and window aspect rather than hard-coded, so the framing survives a different
     * window size; the numbers are logged with every shot.
     */
    private static double distanceForWidth(Minecraft mc, double worldWidth, double widthFraction) {
        return worldWidth / (2.0 * halfWidthTan(mc) * widthFraction);
    }

    private static double halfWidthTan(Minecraft mc) {
        return Math.tan(Math.toRadians(fovDegrees(mc) / 2.0)) * aspect(mc);
    }

    private static double aspect(Minecraft mc) {
        return (double) mc.getWindow().getWidth() / (double) mc.getWindow().getHeight();
    }

    /** {@code options.fov().get()} is the vertical FOV in degrees the projection is built from. */
    private static double fovDegrees(Minecraft mc) {
        Integer fov = mc.options.fov().get();
        return fov == null || fov < 5 ? 70.0 : fov;
    }

    // ------------------------------------------------------------------
    // Phase 3: place the machines on the fixed grid and settle
    // ------------------------------------------------------------------

    private static void gridSetup(Minecraft mc) {
        if (step == 0) {
            serverAction(mc, sp -> {
                ServerLevel level = (ServerLevel) sp.level();
                List<BlockRegistryObject<?, ?>> machines = MekExDeorumBlocks.machines();
                for (int tier = 0; tier < MACHINE_X.length; tier++) {
                    BlockState state = machines.get(tier).defaultState();
                    BlockPos pos = new BlockPos(MACHINE_X[tier], MACHINE_Y, 0);
                    boolean placed = level.setBlock(pos, state, 3);
                    log(mc, "setblock " + pos.toShortString() + " " + TIER_NAMES[tier] + " -> " + placed
                            + ", blockEntity=" + (level.getBlockEntity(pos) == null ? "null" : level.getBlockEntity(pos).getClass().getSimpleName()));
                }
            });
            // Stage the view in the middle of the row so the whole grid is loaded before shooting.
            placePlayer(mc, 30.5, -53.5, 10.5, 0.0F, 0.0F);
            step = 1;
            nextTick = ticks + WAIT_SETTLE;
        } else if (step == 1 && ticks >= nextTick) {
            log(mc, "setup settled at " + fmt(mc.player.getX()) + " " + fmt(mc.player.getY()) + " "
                    + fmt(mc.player.getZ()));
            phase = PHASE_MODEL_SHOTS;
            step = 0;
            armed = false;
        }
    }

    // ------------------------------------------------------------------
    // Phase 4: eight angles per tier
    // ------------------------------------------------------------------

    private static void modelShots(Minecraft mc) {
        int tier = step / ANGLES.length;
        int angle = step % ANGLES.length;
        if (tier >= MACHINE_X.length) {
            log(mc, "model shots done (" + shots + " screenshots so far)");
            phase = PHASE_GUI_SHOTS;
            step = 0;
            armed = false;
            return;
        }
        if (!armed) {
            double cx = MACHINE_X[tier] + 0.5;
            double cy = MACHINE_Y + 0.5;
            double cz = 0.5;
            double eyeX = cx + ANGLES[angle][0];
            double eyeY = cy + ANGLES[angle][1];
            double eyeZ = cz + ANGLES[angle][2];
            placePlayerLookingAt(mc, eyeX, eyeY, eyeZ, cx, cy, cz);
            armed = true;
            nextTick = ticks + WAIT_SHOT;
        } else if (ticks >= nextTick) {
            grab(mc, "model_" + TIER_NAMES[tier] + "_" + ANGLE_NAMES[angle] + ".png");
            armed = false;
            step++;
            nextTick = ticks + 2;
        }
    }

    // ------------------------------------------------------------------
    // Phase 5: one GUI per tier
    // ------------------------------------------------------------------

    private static void guiShots(Minecraft mc) {
        int tier = step / 3;
        int sub = step % 3;
        if (tier >= MACHINE_X.length) {
            log(mc, "GUI shots done (" + shots + " screenshots); starting the functional probe");
            phase = PHASE_PROBE;
            step = 0;
            return;
        }
        BlockPos pos = new BlockPos(MACHINE_X[tier], MACHINE_Y, 0);
        switch (sub) {
            case 0 -> {
                guiFallbackUsed = false;
                placePlayerLookingAt(mc, MACHINE_X[tier] + 0.5, MACHINE_Y + 0.5, 4.0,
                        MACHINE_X[tier] + 0.5, MACHINE_Y + 0.5, 0.5);
                serverAction(mc, sp -> {
                    if (sp.level().getBlockEntity(pos) instanceof TileEntitySieve sieve) {
                        sieve.openGui(sp);
                        log(mc, "asked the server to open the real GUI of " + TIER_NAMES[tier]);
                    } else {
                        log(mc, "no TileEntitySieve at " + pos.toShortString() + " when opening the GUI");
                    }
                });
                step++;
                nextTick = ticks + WAIT_GUI;
            }
            case 1 -> {
                if (ticks < nextTick) {
                    return;
                }
                if (mc.screen instanceof GuiSieve) {
                    log(mc, TIER_NAMES[tier] + ": real container screen is up ("
                            + mc.screen.getClass().getName() + ")");
                } else {
                    guiFallbackUsed = true;
                    log(mc, TIER_NAMES[tier] + ": server route did not put a GuiSieve on screen (screen="
                            + (mc.screen == null ? "null" : mc.screen.getClass().getName())
                            + "), building the screen locally");
                    if (mc.level.getBlockEntity(pos) instanceof TileEntitySieve tile) {
                        Component title = MekExDeorumBlocks.machines().get(tier).getTextComponent();
                        mc.setScreen(new GuiSieve(new SieveContainer(0, mc.player.getInventory(), tile),
                                mc.player.getInventory(), title));
                    } else {
                        log(mc, TIER_NAMES[tier] + ": no client-side TileEntitySieve at "
                                + pos.toShortString() + ", nothing to capture");
                    }
                }
                step++;
                nextTick = ticks + WAIT_SHOT;
            }
            default -> {
                if (ticks < nextTick) {
                    return;
                }
                if (mc.screen instanceof GuiSieve) {
                    grab(mc, "gui_" + TIER_NAMES[tier] + ".png");
                } else {
                    log(mc, TIER_NAMES[tier] + ": no GuiSieve on screen at capture time, GUI not captured");
                }
                if (guiFallbackUsed) {
                    mc.setScreen(null);
                } else if (mc.player != null) {
                    mc.player.closeContainer();
                }
                step++;
                nextTick = ticks + 4;
            }
        }
    }

    // ------------------------------------------------------------------
    // Phase 6: functional probe (test setup only, no behaviour change)
    // ------------------------------------------------------------------

    /**
     * Fills one machine's slots and energy buffer the way a player could and then records what the
     * machine's own tick loop does with them. Nothing here changes machine behaviour; the outputs are
     * read back out of the block entity's own NBT.
     */
    private static void functionalProbe(Minecraft mc) {
        switch (step) {
            case 0 -> {
                serverAction(mc, sp -> {
                    ServerLevel level = (ServerLevel) sp.level();
                    if (!(level.getBlockEntity(probePos()) instanceof TileEntitySieve sieve)) {
                        log(mc, "probe: no machine at " + probePos().toShortString());
                        return;
                    }
                    CompoundTag tag = new CompoundTag();
                    ListTag items = new ListTag();
                    items.add(slotEntry(0, item("exdeorum:string_mesh", 1, null)));
                    items.add(slotEntry(1, item("minecraft:sand", 16, null)));
                    tag.put("items", items);
                    tag.putIntArray("progress", new int[] {0});
                    tag.putByteArray("paused", new byte[] {1});
                    ListTag overflow = new ListTag();
                    overflow.add(overflowEntry("minecraft:dirt", 10L));
                    tag.put("overflow", overflow);
                    sieve.loadWithComponents(tag, level.registryAccess());
                    giveEnergy(sieve, 1_000_000L);
                    log(mc, "probe setup (string mesh, 16 sand, paused with 10 dirt parked, energy injected): "
                            + describe(mc, sieve, level));
                });
                step = 1;
                nextTick = ticks + 20;
            }
            case 1 -> {
                if (ticks < nextTick) {
                    return;
                }
                serverAction(mc, sp -> {
                    if (((ServerLevel) sp.level()).getBlockEntity(probePos()) instanceof TileEntitySieve sieve) {
                        log(mc, "probe after 20 ticks (the parked dirt should have drained and unpaused): "
                                + describe(mc, sieve, (ServerLevel) sp.level()));
                    }
                });
                step = 2;
                nextTick = ticks + 320;
            }
            case 2 -> {
                if (ticks < nextTick) {
                    return;
                }
                serverAction(mc, sp -> {
                    if (((ServerLevel) sp.level()).getBlockEntity(probePos()) instanceof TileEntitySieve sieve) {
                        log(mc, "probe after ~320 more ticks (a completed sift should show its results): "
                                + describe(mc, sieve, (ServerLevel) sp.level()));
                    }
                });
                step = 3;
                nextTick = ticks + 10;
            }
            case 3 -> {
                if (ticks < nextTick) {
                    return;
                }
                serverAction(mc, sp -> {
                    ServerLevel level = (ServerLevel) sp.level();
                    if (level.getBlockEntity(probePos()) instanceof TileEntitySieve sieve) {
                        CompoundTag tag = new CompoundTag();
                        ListTag items = new ListTag();
                        items.add(slotEntry(0, item("exdeorum:iron_mesh", 1, null)));
                        items.add(slotEntry(1, item("minecraft:sand", 8, null)));
                        tag.put("items", items);
                        tag.putIntArray("progress", new int[] {0});
                        tag.putByteArray("paused", new byte[] {0});
                        tag.put("overflow", new ListTag());
                        sieve.loadWithComponents(tag, level.registryAccess());
                        giveEnergy(sieve, 1_000_000L);
                        log(mc, "probe baseline (plain iron mesh, 8 sand): " + describe(mc, sieve, level));
                    }
                });
                step = 4;
                nextTick = ticks + 8;
            }
            case 4 -> {
                if (ticks < nextTick) {
                    return;
                }
                serverAction(mc, sp -> {
                    ServerLevel level = (ServerLevel) sp.level();
                    if (level.getBlockEntity(probePos()) instanceof TileEntitySieve sieve) {
                        log(mc, "probe plain iron mesh, bar computed by the machine: " + describe(mc, sieve, level));
                        CompoundTag enchants = new CompoundTag();
                        enchants.putInt("minecraft:efficiency", 5);
                        CompoundTag tag = new CompoundTag();
                        ListTag items = new ListTag();
                        items.add(slotEntry(0, item("exdeorum:iron_mesh", 1, enchants)));
                        items.add(slotEntry(1, item("minecraft:sand", 8, null)));
                        tag.put("items", items);
                        tag.putIntArray("progress", new int[] {0});
                        tag.putByteArray("paused", new byte[] {0});
                        tag.put("overflow", new ListTag());
                        sieve.loadWithComponents(tag, level.registryAccess());
                        giveEnergy(sieve, 1_000_000L);
                    }
                });
                step = 5;
                nextTick = ticks + 8;
            }
            case 5 -> {
                if (ticks < nextTick) {
                    return;
                }
                serverAction(mc, sp -> {
                    if (((ServerLevel) sp.level()).getBlockEntity(probePos()) instanceof TileEntitySieve sieve) {
                        log(mc, "probe efficiency V iron mesh, bar computed by the machine: "
                                + describe(mc, sieve, (ServerLevel) sp.level()));
                    }
                });
                log(mc, "PROBE DONE (" + shots + " screenshots so far); starting the ejection probe");
                step = 0;
                phase = PHASE_EJECT;
            }
            default -> {
                // Nothing left to do.
            }
        }
    }

    private static BlockPos probePos() {
        return new BlockPos(MACHINE_X[0], MACHINE_Y, 0);
    }

    private static CompoundTag slotEntry(int slot, CompoundTag itemTag) {
        CompoundTag entry = new CompoundTag();
        entry.put("item", itemTag);
        entry.putByte("slot", (byte) slot);
        return entry;
    }

    private static CompoundTag item(String id, int count, @Nullable CompoundTag efficiencyLevels) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id);
        tag.putInt("count", count);
        if (efficiencyLevels != null) {
            CompoundTag enchantments = new CompoundTag();
            enchantments.put("levels", efficiencyLevels);
            CompoundTag components = new CompoundTag();
            components.put("minecraft:enchantments", enchantments);
            tag.put("components", components);
        }
        return tag;
    }

    private static CompoundTag overflowEntry(String id, long amount) {
        CompoundTag entry = new CompoundTag();
        entry.put("item", item(id, 1, null));
        entry.putLong("amount", amount);
        return entry;
    }

    private static void giveEnergy(TileEntitySieve sieve, long amount) {
        List<mekanism.api.energy.IEnergyContainer> containers = sieve.getEnergyContainers(null);
        if (containers.isEmpty()) {
            LOG.warn("[F4 harness] {}: no energy container to fill", "probe");
            return;
        }
        containers.get(0).setEnergy(amount);
    }

    /** Reads the machine's own live NBT back: energy, per-slot bars, buffer and slot contents. */
    private static String describe(Minecraft mc, TileEntitySieve sieve, ServerLevel level) {
        CompoundTag tag = sieve.saveWithoutMetadata(level.registryAccess());
        List<mekanism.api.energy.IEnergyContainer> containers = sieve.getEnergyContainers(null);
        StringBuilder builder = new StringBuilder();
        builder.append("energy=").append(containers.isEmpty() ? -1L : containers.get(0).getEnergy())
                .append(" progress=").append(java.util.Arrays.toString(tag.getIntArray("progress")))
                .append(" requiredTicks=").append(java.util.Arrays.toString(tag.getIntArray("requiredTicks")))
                .append(" batchSize=").append(java.util.Arrays.toString(tag.getIntArray("batchSize")))
                .append(" paused=").append(java.util.Arrays.toString(tag.getByteArray("paused")))
                .append(" overflow=").append(tag.getList("overflow", Tag.TAG_COMPOUND))
                .append(" slots=");
        ListTag items = tag.getList("items", Tag.TAG_COMPOUND);
        for (int index = 0; index < items.size(); index++) {
            CompoundTag itemTag = items.getCompound(index).getCompound("item");
            builder.append('[').append(items.getCompound(index).getByte("slot")).append(' ')
                    .append(itemTag.getString("id")).append(" x").append(itemTag.getInt("count")).append("] ");
        }
        return builder.toString();
    }

    // ------------------------------------------------------------------
    // Phase 7: ejection probe — which faces the machine really pushes to
    // ------------------------------------------------------------------

    private static final int EJECT_X = 50;
    private static final int EJECT_Y = -54;
    private static final int EJECT_Z = 0;
    /** Cobblestone parked in one output slot for the single-tick push test; a barrel holds 27 * 64. */
    private static final int EJECT_BULK = 3000;
    /** Cobblestone parked for the throttle test. */
    private static final int EJECT_IDLE_ITEMS = 64;

    /** Ticks the change-detecting sampler runs per test. */
    private static final int EJECT_SAMPLE_TICKS = 60;
    /** Ticks the throttle sampler runs: long enough to cross the 200-tick idle threshold twice. */
    private static final int EJECT_THROTTLE_TICKS = 300;

    private static boolean ejectSampling;
    private static int ejectSampleKind;
    private static int ejectResumeStep;
    private static int ejectSampleUntil;
    private static int ejectSamples;
    private static String ejectPrevSignature;
    private static long ejectPrevGameTime = -1L;
    private static long ejectFirstChangeGameTime = -1L;
    private static int ejectFirstChangeSample = -1;
    private static String ejectFirstChange;
    private static long ejectCooldownOnsetGameTime = -1L;
    private static int ejectCooldownOnsetIdle = -1;
    private static long ejectResumeGameTime = -1L;

    private static BlockPos ejectPos() {
        return new BlockPos(EJECT_X, EJECT_Y, EJECT_Z);
    }

    private static void ejectionProbe(Minecraft mc) {
        if (ejectSampling) {
            sampleEjection(mc);
            return;
        }
        switch (step) {
            case 0 -> ejectSetup(mc);
            case 1 -> ejectCheck1Setup(mc);
            case 2 -> ejectCheck1Read(mc);
            case 3 -> ejectCheck2Setup(mc);
            case 4 -> ejectCheck2Read(mc);
            case 5 -> ejectCheck3Setup(mc);
            case 6 -> ejectCheck3Read(mc);
            case 7 -> ejectCheck4Setup(mc);
            case 8 -> ejectCheck4Read(mc);
            case 9 -> ejectCheck5Idle(mc);
            case 10 -> ejectCheck5Resume(mc);
            case 11 -> ejectCheck5Read(mc);
            case 12 -> ejectCheck6Open(mc);
            case 13 -> ejectCheck6Tab(mc);
            case 14 -> ejectCheck6Shot(mc);
            default -> {
                // Finished: nothing further is done, the game is left running for collection.
            }
        }
    }

    /** Places the machine, one barrel on each of its six sides, and records the starting state. */
    private static void ejectSetup(Minecraft mc) {
        if (step == 0) {
            placePlayer(mc, EJECT_X + 0.5, EJECT_Y + 1.5, EJECT_Z - 4.5, 0.0F, 0.0F);
            serverAction(mc, sp -> {
                ServerLevel level = (ServerLevel) sp.level();
                BlockPos pos = ejectPos();
                // Cleared first, so the block entity is built from scratch instead of inheriting the
                // inventory and side config a previous run left in the save.
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                level.setBlock(pos, MekExDeorumBlocks.machines().get(0).defaultState(), 3);
                for (Direction direction : Direction.values()) {
                    level.setBlock(pos.relative(direction), Blocks.AIR.defaultBlockState(), 3);
                    level.setBlock(pos.relative(direction), Blocks.BARREL.defaultBlockState(), 3);
                }
                if (!(level.getBlockEntity(pos) instanceof TileEntitySieve sieve)) {
                    log(mc, "ejection probe: no machine at " + pos.toShortString());
                    return;
                }
                log(mc, "ejection probe setup: machine " + pos.toShortString() + " inputSlots="
                        + sieve.getInputSlotCount() + " outputSlots=" + sieve.getOutputSlotCount()
                        + " dynamicEject=" + com.mekexdeorum.MekExDeorumConfig.dynamicEject());
                log(mc, "ejection probe side config exactly as the machine was built: " + describeSides(sieve));
                applySideConfig(sieve, new EnumMap<>(RelativeSide.class));
                log(mc, "ejection probe side config after setting every side to NONE: " + describeSides(sieve));
                log(mc, "ejection probe auto-eject flag as the machine was built (isEjecting)=" + isEjecting(sieve));
                StringBuilder handlers = new StringBuilder("ejection probe item handlers on the six sides:");
                for (Direction direction : Direction.values()) {
                    IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK,
                            pos.relative(direction), direction.getOpposite());
                    handlers.append(' ').append(direction.getName()).append('=')
                            .append(handler == null ? "null" : handler.getSlots() + " slots");
                }
                log(mc, handlers.toString());
                int[] counters = throttleCounters(sieve);
                log(mc, "ejection probe ejectThrottle readable by reflection: " + (counters[0] >= 0)
                        + " (idleTicks=" + counters[0] + ", cooldown=" + counters[1] + ")");
            });
            step = 1;
            nextTick = ticks + 20;
        }
    }

    /** Check 1: exactly one output face, every other face a different non-output type. */
    private static void ejectCheck1Setup(Minecraft mc) {
        if (ticks < nextTick) {
            return;
        }
        serverAction(mc, sp -> {
            ServerLevel level = (ServerLevel) sp.level();
            if (!(level.getBlockEntity(ejectPos()) instanceof TileEntitySieve sieve)) {
                log(mc, "check 1: no machine");
                return;
            }
            Map<RelativeSide, DataType> sides = new EnumMap<>(RelativeSide.class);
            sides.put(RelativeSide.BACK, DataType.OUTPUT);
            sides.put(RelativeSide.FRONT, DataType.INPUT);
            sides.put(RelativeSide.LEFT, DataType.INPUT_OUTPUT);
            sides.put(RelativeSide.RIGHT, DataType.EXTRA);
            sides.put(RelativeSide.TOP, DataType.ENERGY);
            sides.put(RelativeSide.BOTTOM, DataType.NONE);
            clearBarrels(level);
            parkOutput(level, sieve, new String[] {
                    "minecraft:cobblestone", "minecraft:dirt", "minecraft:gravel", "minecraft:sand"},
                    new int[] {64, 32, 16, 8});
            applySideConfig(sieve, sides);
            log(mc, "check 1 setup, one OUTPUT face (auto eject forced on -> " + setEjecting(sieve, true) + "): "
                    + describeSideConfig(sieve));
            log(mc, "check 1 before: " + ejectSnapshot(level, sieve));
        });
        step = 2;
        nextTick = ticks + 12;
    }

    private static void ejectCheck1Read(Minecraft mc) {
        if (ticks < nextTick) {
            return;
        }
        serverAction(mc, sp -> {
            ServerLevel level = (ServerLevel) sp.level();
            if (level.getBlockEntity(ejectPos()) instanceof TileEntitySieve sieve) {
                log(mc, "check 1 after 12 ticks: " + ejectSnapshot(level, sieve));
                log(mc, "check 1 side config read back from the machine: " + describeSideConfig(sieve));
            }
        });
        step = 3;
        nextTick = ticks + 2;
    }

    /** Check 2: the very same face, switched from OUTPUT to INPUT, refilled. */
    private static void ejectCheck2Setup(Minecraft mc) {
        if (ticks < nextTick) {
            return;
        }
        serverAction(mc, sp -> {
            ServerLevel level = (ServerLevel) sp.level();
            if (!(level.getBlockEntity(ejectPos()) instanceof TileEntitySieve sieve)) {
                log(mc, "check 2: no machine");
                return;
            }
            Map<RelativeSide, DataType> sides = new EnumMap<>(RelativeSide.class);
            sides.put(RelativeSide.BACK, DataType.INPUT);
            sides.put(RelativeSide.FRONT, DataType.INPUT);
            sides.put(RelativeSide.LEFT, DataType.INPUT);
            clearBarrels(level);
            parkOutput(level, sieve, new String[] {"minecraft:cobblestone", "minecraft:dirt"},
                    new int[] {64, 32});
            applySideConfig(sieve, sides);
            log(mc, "check 2 setup, the OUTPUT face switched to INPUT and refilled (auto eject on -> "
                    + setEjecting(sieve, true) + "): " + describeSideConfig(sieve));
            log(mc, "check 2 before: " + ejectSnapshot(level, sieve));
        });
        step = 4;
        nextTick = ticks + 12;
    }

    private static void ejectCheck2Read(Minecraft mc) {
        if (ticks < nextTick) {
            return;
        }
        serverAction(mc, sp -> {
            ServerLevel level = (ServerLevel) sp.level();
            if (level.getBlockEntity(ejectPos()) instanceof TileEntitySieve sieve) {
                log(mc, "check 2 after 12 ticks: " + ejectSnapshot(level, sieve));
            }
        });
        step = 5;
        nextTick = ticks + 2;
    }

    /** Check 3: three output faces at once. */
    private static void ejectCheck3Setup(Minecraft mc) {
        if (ticks < nextTick) {
            return;
        }
        serverAction(mc, sp -> {
            ServerLevel level = (ServerLevel) sp.level();
            if (!(level.getBlockEntity(ejectPos()) instanceof TileEntitySieve sieve)) {
                log(mc, "check 3: no machine");
                return;
            }
            Map<RelativeSide, DataType> sides = new EnumMap<>(RelativeSide.class);
            sides.put(RelativeSide.BACK, DataType.OUTPUT);
            sides.put(RelativeSide.RIGHT, DataType.OUTPUT);
            sides.put(RelativeSide.TOP, DataType.OUTPUT);
            sides.put(RelativeSide.FRONT, DataType.INPUT);
            clearBarrels(level);
            parkOutput(level, sieve, new String[] {"minecraft:cobblestone"}, new int[] {4000});
            applySideConfig(sieve, sides);
            log(mc, "check 3 setup, three OUTPUT faces, 4000 parked (one barrel holds 1728), auto eject on -> "
                    + setEjecting(sieve, true) + ": " + describeSides(sieve));
            log(mc, "check 3 before: " + ejectSnapshot(level, sieve));
        });
        step = 6;
        nextTick = ticks + 12;
    }

    private static void ejectCheck3Read(Minecraft mc) {
        if (ticks < nextTick) {
            return;
        }
        serverAction(mc, sp -> {
            ServerLevel level = (ServerLevel) sp.level();
            if (level.getBlockEntity(ejectPos()) instanceof TileEntitySieve sieve) {
                log(mc, "check 3 after 12 ticks: " + ejectSnapshot(level, sieve));
            }
        });
        step = 7;
        nextTick = ticks + 2;
    }

    /** Check 4: thousands in one output slot, one accepting barrel, sampled every client tick. */
    private static void ejectCheck4Setup(Minecraft mc) {
        if (ticks < nextTick) {
            return;
        }
        serverAction(mc, sp -> {
            ServerLevel level = (ServerLevel) sp.level();
            if (!(level.getBlockEntity(ejectPos()) instanceof TileEntitySieve sieve)) {
                log(mc, "check 4: no machine");
                return;
            }
            Map<RelativeSide, DataType> sides = new EnumMap<>(RelativeSide.class);
            sides.put(RelativeSide.BACK, DataType.OUTPUT);
            clearBarrels(level);
            parkOutput(level, sieve, new String[] {"minecraft:cobblestone"}, new int[] {EJECT_BULK});
            applySideConfig(sieve, sides);
            log(mc, "check 4 setup: " + EJECT_BULK + " cobblestone in output slot "
                    + (sieve.getInputSlotCount() + 1) + ", one OUTPUT face, empty barrel on it (auto eject on -> "
                    + setEjecting(sieve, true) + "): " + describeSideConfig(sieve));
            log(mc, "check 4 before: " + ejectSnapshot(level, sieve));
        });
        startSampling(1, EJECT_SAMPLE_TICKS, 8);
        nextTick = ticks + 2;
    }

    private static void ejectCheck4Read(Minecraft mc) {
        if (ticks < nextTick) {
            return;
        }
        serverAction(mc, sp -> {
            ServerLevel level = (ServerLevel) sp.level();
            if (level.getBlockEntity(ejectPos()) instanceof TileEntitySieve sieve) {
                log(mc, "check 4 after the sampler: " + ejectSnapshot(level, sieve));
            }
        });
        log(mc, "check 4 sampler summary: " + changeSummary());
        step = 9;
        nextTick = ticks + 2;
    }

    /** Check 5, part one: no accepting neighbour at all, so the throttle has to engage. */
    private static void ejectCheck5Idle(Minecraft mc) {
        if (ticks < nextTick) {
            return;
        }
        serverAction(mc, sp -> {
            ServerLevel level = (ServerLevel) sp.level();
            if (!(level.getBlockEntity(ejectPos()) instanceof TileEntitySieve sieve)) {
                log(mc, "check 5: no machine");
                return;
            }
            Direction back = RelativeSide.BACK.getDirection(sieve.getDirection());
            Map<RelativeSide, DataType> sides = new EnumMap<>(RelativeSide.class);
            sides.put(RelativeSide.BACK, DataType.OUTPUT);
            clearBarrels(level);
            level.setBlock(ejectPos().relative(back), Blocks.AIR.defaultBlockState(), 3);
            parkOutput(level, sieve, new String[] {"minecraft:cobblestone"}, new int[] {EJECT_IDLE_ITEMS});
            applySideConfig(sieve, sides);
            log(mc, "check 5 setup: " + EJECT_IDLE_ITEMS + " cobblestone parked, OUTPUT face "
                    + RelativeSide.BACK + "->" + back + " has no neighbour at all (auto eject on -> "
                    + setEjecting(sieve, true) + "): " + ejectSnapshot(level, sieve)
                    + " || " + describeSides(sieve));
        });
        startSampling(2, EJECT_THROTTLE_TICKS, 10);
        nextTick = ticks + 2;
    }

    /** Check 5, part two: put an accepting barrel on the output face while it may still be throttled. */
    private static void ejectCheck5Resume(Minecraft mc) {
        if (ticks < nextTick) {
            return;
        }
        serverAction(mc, sp -> {
            ServerLevel level = (ServerLevel) sp.level();
            if (!(level.getBlockEntity(ejectPos()) instanceof TileEntitySieve sieve)) {
                log(mc, "check 5 resume: no machine");
                return;
            }
            Direction back = RelativeSide.BACK.getDirection(sieve.getDirection());
            level.setBlock(ejectPos().relative(back), Blocks.BARREL.defaultBlockState(), 3);
            parkOutput(level, sieve, new String[] {"minecraft:cobblestone"}, new int[] {EJECT_IDLE_ITEMS});
            applySideConfig(sieve, Map.of(RelativeSide.BACK, DataType.OUTPUT));
            ejectResumeGameTime = level.getGameTime();
            int[] counters = throttleCounters(sieve);
            log(mc, "check 5 resume: empty barrel placed on " + back + " and " + EJECT_IDLE_ITEMS
                    + " cobblestone re-parked at gt=" + ejectResumeGameTime
                    + " while idleTicks=" + counters[0] + " cooldown=" + counters[1]
                    + " (auto eject on -> " + setEjecting(sieve, true) + ")");
            log(mc, "check 5 resume before: " + ejectSnapshot(level, sieve) + " || " + describeSides(sieve));
        });
        startSampling(1, 40, 11);
        nextTick = ticks + 2;
    }

    private static void ejectCheck5Read(Minecraft mc) {
        if (ticks < nextTick) {
            return;
        }
        serverAction(mc, sp -> {
            ServerLevel level = (ServerLevel) sp.level();
            if (level.getBlockEntity(ejectPos()) instanceof TileEntitySieve sieve) {
                int[] counters = throttleCounters(sieve);
                log(mc, "check 5 after the resume sampler: " + ejectSnapshot(level, sieve)
                        + " idleTicks=" + counters[0] + " cooldown=" + counters[1]);
            }
        });
        log(mc, "check 5 sampler summary: barrel placed at gt=" + ejectResumeGameTime + "; " + changeSummary());
        log(mc, "check 5 throttle onset: cooldown first seen at gt=" + ejectCooldownOnsetGameTime
                + " with idleTicks=" + ejectCooldownOnsetIdle);
        step = 12;
        nextTick = ticks + 2;
    }

    /** Check 6: the real GUI of the machine, then its side-configuration page. */
    private static void ejectCheck6Open(Minecraft mc) {
        if (ticks < nextTick) {
            return;
        }
        serverAction(mc, sp -> {
            ServerLevel level = (ServerLevel) sp.level();
            if (!(level.getBlockEntity(ejectPos()) instanceof TileEntitySieve sieve)) {
                log(mc, "check 6: no machine");
                return;
            }
            Map<RelativeSide, DataType> sides = new EnumMap<>(RelativeSide.class);
            sides.put(RelativeSide.BACK, DataType.OUTPUT);
            sides.put(RelativeSide.LEFT, DataType.OUTPUT);
            sides.put(RelativeSide.FRONT, DataType.INPUT);
            sides.put(RelativeSide.RIGHT, DataType.INPUT_OUTPUT);
            applySideConfig(sieve, sides);
            log(mc, "check 6 config before opening the GUI (auto eject on -> " + setEjecting(sieve, true) + "): "
                    + describeSideConfig(sieve));
            sieve.openGui(sp);
        });
        placePlayerLookingAt(mc, EJECT_X + 0.5, EJECT_Y + 0.5, EJECT_Z + 4.0,
                EJECT_X + 0.5, EJECT_Y + 0.5, EJECT_Z + 0.5);
        step = 13;
        nextTick = ticks + WAIT_GUI;
    }

    private static void ejectCheck6Tab(Minecraft mc) {
        if (ticks < nextTick) {
            return;
        }
        boolean gui = mc.screen instanceof GuiSieve;
        boolean clicked = openSideConfigWindow(mc);
        log(mc, "check 6: real GuiSieve on screen=" + gui + " ("
                + (mc.screen == null ? "null" : mc.screen.getClass().getName())
                + "), side-configuration tab clicked=" + clicked + ", windows on screen=" + countWindows(mc));
        step = 14;
        nextTick = ticks + 6;
    }

    private static void ejectCheck6Shot(Minecraft mc) {
        if (ticks < nextTick) {
            return;
        }
        log(mc, "check 6: windows on screen before the shot=" + countWindows(mc));
        grab(mc, "side_config_ejection_probe.png");
        if (mc.player != null) {
            mc.player.closeContainer();
        }
        step = 0;
        phase = PHASE_EJECT_GATE;
    }

    // ------------------------------------------------------------------
    // Phase 8: the auto-eject gate (ConfigInfo#isEjecting)
    // ------------------------------------------------------------------

    /**
     * The ITEM config's auto-eject flag has to gate ejection: with the flag off nothing may leave the
     * machine even when a side is marked OUTPUT, and turning it on has to start the push again. The
     * flag is flipped exactly the way the configuration packet flips it.
     */
    private static void ejectGateProbe(Minecraft mc) {
        switch (step) {
            case 0 -> {
                serverAction(mc, sp -> {
                    ServerLevel level = (ServerLevel) sp.level();
                    if (!(level.getBlockEntity(ejectPos()) instanceof TileEntitySieve sieve)) {
                        log(mc, "gate check: no machine");
                        return;
                    }
                    Direction back = RelativeSide.BACK.getDirection(sieve.getDirection());
                    level.setBlock(ejectPos().relative(back), Blocks.BARREL.defaultBlockState(), 3);
                    clearBarrels(level);
                    parkOutput(level, sieve, new String[] {"minecraft:cobblestone"}, new int[] {64});
                    applySideConfig(sieve, Map.of(RelativeSide.BACK, DataType.OUTPUT));
                    boolean on = setEjecting(sieve, false);
                    log(mc, "gate check setup: OUTPUT face " + back + ", 64 parked, auto eject forced OFF "
                            + "(isEjecting now " + on + "): " + ejectSnapshot(level, sieve)
                            + " || " + describeSides(sieve));
                });
                step = 1;
                nextTick = ticks + 20;
            }
            case 1 -> {
                if (ticks < nextTick) {
                    return;
                }
                serverAction(mc, sp -> {
                    ServerLevel level = (ServerLevel) sp.level();
                    if (level.getBlockEntity(ejectPos()) instanceof TileEntitySieve sieve) {
                        log(mc, "gate check with auto eject OFF after 20 ticks: " + ejectSnapshot(level, sieve)
                                + " isEjecting=" + isEjecting(sieve));
                    }
                });
                step = 2;
                nextTick = ticks + 2;
            }
            case 2 -> {
                if (ticks < nextTick) {
                    return;
                }
                serverAction(mc, sp -> {
                    ServerLevel level = (ServerLevel) sp.level();
                    if (!(level.getBlockEntity(ejectPos()) instanceof TileEntitySieve sieve)) {
                        log(mc, "gate check: no machine");
                        return;
                    }
                    parkOutput(level, sieve, new String[] {"minecraft:cobblestone"}, new int[] {64});
                    boolean on = setEjecting(sieve, true);
                    log(mc, "gate check auto eject turned ON (isEjecting now " + on
                            + "), 64 parked again: " + ejectSnapshot(level, sieve));
                });
                step = 3;
                nextTick = ticks + 12;
            }
            case 3 -> {
                if (ticks < nextTick) {
                    return;
                }
                serverAction(mc, sp -> {
                    ServerLevel level = (ServerLevel) sp.level();
                    if (level.getBlockEntity(ejectPos()) instanceof TileEntitySieve sieve) {
                        log(mc, "gate check with auto eject ON after 12 ticks: " + ejectSnapshot(level, sieve)
                                + " isEjecting=" + isEjecting(sieve));
                    }
                });
                log(mc, "EJECTION PROBE DONE (" + shots + " screenshots); starting the even-split button probe");
                step = 0;
                phase = PHASE_SPLIT;
            }
            default -> {
                // Finished.
            }
        }
    }

    /**
     * Flips the ITEM config's auto-eject flag exactly the way the configuration packet does
     * ({@code ConfigInfo#setEjecting} followed by {@code TileEntityMekanism#markForSave}), and
     * returns the state the machine reports afterwards.
     */
    private static boolean setEjecting(TileEntitySieve sieve, boolean ejecting) {
        ConfigInfo info = sieve.getConfig().getConfig(TransmissionType.ITEM);
        if (info == null) {
            return false;
        }
        info.setEjecting(ejecting);
        sieve.markForSave();
        return info.isEjecting();
    }

    private static boolean isEjecting(TileEntitySieve sieve) {
        ConfigInfo info = sieve.getConfig().getConfig(TransmissionType.ITEM);
        return info != null && info.isEjecting();
    }

    /**
     * Assigns the ITEM data types of all six sides the way the configuration packet does it:
     * {@code ConfigInfo.setDataType} followed by {@code TileComponentConfig.sideChanged}. Sides that
     * the map does not mention are set to {@link DataType#NONE}.
     */
    private static void applySideConfig(TileEntitySieve sieve, Map<RelativeSide, DataType> assignment) {
        ConfigInfo info = sieve.getConfig().getConfig(TransmissionType.ITEM);
        for (RelativeSide side : RelativeSide.values()) {
            DataType type = assignment.getOrDefault(side, DataType.NONE);
            boolean accepted = info.setDataType(type, side);
            sieve.getConfig().sideChanged(TransmissionType.ITEM, side);
            if (!accepted) {
                log(null, "ejection probe: setDataType(" + type + ", " + side + ") was refused");
            }
        }
    }

    /** What the machine itself thinks its six sides are, plus the barrel total behind each one. */
    private static String describeSideConfig(TileEntitySieve sieve) {
        Direction facing = sieve.getDirection();
        ConfigInfo info = sieve.getConfig().getConfig(TransmissionType.ITEM);
        ServerLevel level = (ServerLevel) sieve.getLevel();
        StringBuilder builder = new StringBuilder("facing=" + facing);
        for (RelativeSide side : RelativeSide.values()) {
            Direction direction = side.getDirection(facing);
            builder.append(' ').append(side).append("->").append(direction).append('=')
                    .append(info.getDataType(side))
                    .append("(barrel ").append(containerCount(level, sieve.getBlockPos().relative(direction)))
                    .append(')');
        }
        return builder.toString();
    }

    /** One comparable line: the game time, the six barrels and the machine's own output slots. */
    private static String ejectSnapshot(ServerLevel level, TileEntitySieve sieve) {
        StringBuilder builder = new StringBuilder("gt=").append(level.getGameTime());
        for (Direction direction : Direction.values()) {
            builder.append(' ').append(direction.getName()).append('=')
                    .append(containerCount(level, ejectPos().relative(direction)));
        }
        builder.append(" outputs=").append(outputSummary(level, sieve));
        return builder.toString();
    }

    /** The same line without the game time, so two samples can be compared. */
    private static String ejectSignature(ServerLevel level, TileEntitySieve sieve) {
        String snapshot = ejectSnapshot(level, sieve);
        int split = snapshot.indexOf(' ');
        return split < 0 ? snapshot : snapshot.substring(split + 1);
    }

    /** Contents of the machine's own output slots, read straight off the live slot objects. */
    private static String outputSummary(ServerLevel level, TileEntitySieve sieve) {
        List<mekanism.api.inventory.IInventorySlot> slots = sieve.getInventorySlots(null);
        int first = sieve.getInputSlotCount() + 1;
        int last = first + sieve.getOutputSlotCount() - 1;
        StringBuilder builder = new StringBuilder();
        for (int index = first; index <= last && index < slots.size(); index++) {
            ItemStack stack = slots.get(index).getStack();
            if (stack.isEmpty()) {
                continue;
            }
            builder.append('[').append(index).append(' ')
                    .append(BuiltInRegistries.ITEM.getKey(stack.getItem())).append(" x")
                    .append(stack.getCount()).append(']');
        }
        return builder.isEmpty() ? "empty" : builder.toString();
    }

    /**
     * Both views of the side config: the raw map the machine's ejectSides() iterates, and what
     * {@code getDataType} reports for each relative side.
     */
    private static String describeSides(TileEntitySieve sieve) {
        Direction facing = sieve.getDirection();
        ConfigInfo info = sieve.getConfig().getConfig(TransmissionType.ITEM);
        StringBuilder builder = new StringBuilder("facing=").append(facing).append(" raw={");
        for (Map.Entry<RelativeSide, DataType> entry : info.getSideConfig()) {
            builder.append(entry.getKey()).append("->").append(entry.getKey().getDirection(facing)).append('=')
                    .append(entry.getValue()).append(' ');
        }
        builder.append("} typed={");
        for (RelativeSide side : RelativeSide.values()) {
            builder.append(side).append('=').append(info.getDataType(side)).append(' ');
        }
        return builder.append('}').toString();
    }

    private static String neighbourBlocks(ServerLevel level) {
        StringBuilder builder = new StringBuilder("blocks=");
        for (Direction direction : Direction.values()) {
            builder.append(direction.getName()).append(':')
                    .append(BuiltInRegistries.BLOCK.getKey(
                            level.getBlockState(ejectPos().relative(direction)).getBlock()))
                    .append(' ');
        }
        return builder.toString();
    }

    private static int containerCount(ServerLevel level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof Container container) {
            int total = 0;
            for (int index = 0; index < container.getContainerSize(); index++) {
                total += container.getItem(index).getCount();
            }
            return total;
        }
        return -1;
    }

    private static void clearBarrels(ServerLevel level) {
        for (Direction direction : Direction.values()) {
            if (level.getBlockEntity(ejectPos().relative(direction)) instanceof Container container
                    && !(container instanceof TileEntitySieve)) {
                container.clearContent();
                container.setChanged();
            }
        }
    }

    /** Replaces the machine's items with the given amounts parked in consecutive output slots. */
    private static void parkOutput(ServerLevel level, TileEntitySieve sieve, String[] ids, int[] counts) {
        CompoundTag tag = new CompoundTag();
        ListTag items = new ListTag();
        int first = sieve.getInputSlotCount() + 1;
        for (int index = 0; index < ids.length && index < counts.length; index++) {
            items.add(slotEntry(first + index, item(ids[index], counts[index], null)));
        }
        tag.put("items", items);
        sieve.loadWithComponents(tag, level.registryAccess());
    }

    private static void startSampling(int kind, int duration, int resumeStep) {
        ejectSampling = true;
        ejectSampleKind = kind;
        ejectResumeStep = resumeStep;
        ejectSampleUntil = ticks + duration;
        ejectSamples = 0;
        ejectPrevSignature = null;
        ejectPrevGameTime = -1L;
        ejectFirstChange = null;
        ejectFirstChangeGameTime = -1L;
        ejectFirstChangeSample = -1;
    }

    /**
     * One sample per client tick, taken on the server thread. Kind 1 logs every change with the
     * number of server ticks since the previous sample; kind 2 logs the throttle counters.
     */
    private static void sampleEjection(Minecraft mc) {
        serverAction(mc, sp -> {
            ServerLevel level = (ServerLevel) sp.level();
            if (!(level.getBlockEntity(ejectPos()) instanceof TileEntitySieve sieve)) {
                return;
            }
            ejectSamples++;
            long gameTime = level.getGameTime();
            if (ejectSampleKind == 2) {
                int[] counters = throttleCounters(sieve);
                if (ejectSamples % 10 == 1 || (counters[1] > 0 && ejectCooldownOnsetGameTime < 0)) {
                    log(mc, "throttle gt=" + gameTime + " idleTicks=" + counters[0] + " cooldown="
                            + counters[1] + " outputs=" + outputSummary(level, sieve) + " || "
                            + describeSides(sieve));
                }
                if (counters[1] > 0 && ejectCooldownOnsetGameTime < 0) {
                    ejectCooldownOnsetGameTime = gameTime;
                    ejectCooldownOnsetIdle = counters[0];
                }
            }
            String signature = ejectSignature(level, sieve);
            if (ejectPrevSignature == null) {
                log(mc, "sample gt=" + gameTime + " initial: " + signature + " || " + describeSides(sieve));
            } else if (!signature.equals(ejectPrevSignature)) {
                long delta = gameTime - ejectPrevGameTime;
                log(mc, "sample gt=" + gameTime + " after " + delta + " tick(s): " + signature
                        + "  (was " + ejectPrevSignature + ") || " + describeSides(sieve)
                        + " || " + neighbourBlocks(level));
                if (ejectFirstChange == null) {
                    ejectFirstChange = signature;
                    ejectFirstChangeGameTime = gameTime;
                    ejectFirstChangeSample = ejectSamples;
                }
            }
            ejectPrevSignature = signature;
            ejectPrevGameTime = gameTime;
        });
        if (ticks >= ejectSampleUntil) {
            ejectSampling = false;
            log(mc, "ejection sampler done after " + ejectSamples + " samples");
            step = ejectResumeStep;
            nextTick = ticks + 2;
        }
    }

    /** One line about the first change the sampler saw, or that it saw none. */
    private static String changeSummary() {
        if (ejectFirstChange == null) {
            return "the sampler saw no change in " + ejectSamples + " samples";
        }
        return "first change at gt=" + ejectFirstChangeGameTime + " (sample " + ejectFirstChangeSample
                + ") -> " + ejectFirstChange;
    }

    /** {idleTicks, cooldown} of the machine's own throttle, or {-1, -1} if it cannot be read. */
    private static int[] throttleCounters(TileEntitySieve sieve) {
        try {
            Field field = TileEntitySieve.class.getDeclaredField("ejectThrottle");
            field.setAccessible(true);
            Object throttle = field.get(sieve);
            if (throttle == null) {
                return new int[] {-1, -1};
            }
            Method idle = throttle.getClass().getMethod("idleTicks");
            Method cooldown = throttle.getClass().getMethod("cooldown");
            return new int[] {(Integer) idle.invoke(throttle), (Integer) cooldown.invoke(throttle)};
        } catch (Throwable throwable) {
            return new int[] {-1, -1};
        }
    }

    /** Clicks the side-configuration tab of the open machine GUI, the way a player would. */
    private static boolean openSideConfigWindow(Minecraft mc) {
        if (mc.screen == null) {
            return false;
        }
        for (GuiEventListener child : mc.screen.children()) {
            if (child instanceof GuiSideConfigurationTab<?> tab) {
                tab.onClick(tab.getX() + tab.getWidth() / 2.0, tab.getY() + tab.getHeight() / 2.0, 0);
                return true;
            }
        }
        return false;
    }

    private static int countWindows(Minecraft mc) {
        if (mc.screen == null) {
            return -1;
        }
        int found = 0;
        for (GuiEventListener child : mc.screen.children()) {
            if (child instanceof GuiWindow) {
                found++;
            }
        }
        return found;
    }

    // ------------------------------------------------------------------
    // Phase 9: the auto-split toggle (round 6b)
    // ------------------------------------------------------------------

    /** Tier index of the nine-input machine (ultimate_sieve_factory). */
    private static final int SPLIT_NINE_TIER = 4;
    /** Tier index of the one-input machine. */
    private static final int SPLIT_ONE_TIER = 0;
    /** Items parked in the very first input slot before the first rebalance (60 = 6 * 9 + 6). */
    private static final int SPLIT_ITEMS = 60;
    /** Items added to a single input slot while the mode is off. */
    private static final int SPLIT_EXTRA = 45;

    private static String splitPrevSummary = "";

    private static BlockPos gridPos(int tier) {
        return new BlockPos(MACHINE_X[tier], MACHINE_Y, 0);
    }

    /**
     * Proves the auto-split <em>toggle</em>, not a one-off split: click on, the row rebalances and then
     * stays byte-identical without churn; click off, a later uneven insert stays lopsided; click on
     * again, the row rebalances a second time; and once the machine is given a mesh it keeps the row
     * balanced while it consumes the material itself.
     *
     * <p>Every state change goes through the real client path — the toggle widget's own click handler,
     * which sends vanilla's container-button packet — and nothing here calls the machine's toggle or
     * split method directly.
     */
    private static void splitProbe(Minecraft mc) {
        switch (step) {
            case 0 -> {   // nine-input machine, mode off, 60 sand in the first input slot only
                if (ticks < nextTick) {
                    return;
                }
                BlockPos pos = gridPos(SPLIT_NINE_TIER);
                placePlayerLookingAt(mc, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 4.0,
                        pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                serverAction(mc, sp -> {
                    ServerLevel level = (ServerLevel) sp.level();
                    if (!(level.getBlockEntity(pos) instanceof TileEntitySieve sieve)) {
                        log(mc, "auto-split: no machine at " + pos.toShortString());
                        return;
                    }
                    sieve.setAutoSplit(false);
                    parkFirstInput(sieve, SPLIT_ITEMS);
                    giveEnergy(sieve, 1_000_000L);
                    log(mc, "auto-split layout (GUI coordinates): " + splitLayout(sieve));
                    log(mc, "auto-split setup, mode OFF (default), " + SPLIT_ITEMS
                            + " sand in input slot 1 only: " + autoSplitState(sieve, level));
                    sieve.openGui(sp);
                });
                step = 1;
                nextTick = ticks + WAIT_GUI + 6;
            }
            case 1 -> {   // the OFF shot, and proof that an arrival is not redistributed while off
                if (ticks < nextTick) {
                    return;
                }
                BlockPos pos = gridPos(SPLIT_NINE_TIER);
                log(mc, "auto-split: screen=" + (mc.screen == null ? "null" : mc.screen.getClass().getName())
                        + " " + toggleRect(mc) + " client side autoSplit=" + clientAutoSplit(mc, pos));
                grab(mc, "gui_ultimate_autosplit_off.png");
                serverAction(mc, sp -> {
                    ServerLevel level = (ServerLevel) sp.level();
                    if (level.getBlockEntity(pos) instanceof TileEntitySieve sieve) {
                        log(mc, "auto-split mode OFF after the machine ticked: " + autoSplitState(sieve, level));
                    }
                });
                step = 2;
                nextTick = ticks + 6;
            }
            case 2 -> {   // click 1: the widget itself switches the mode on
                if (ticks < nextTick) {
                    return;
                }
                clickToggle(mc, "click 1 -> expects ON");
                step = 3;
                nextTick = ticks + 8;
            }
            case 3 -> {   // the rebalance ran on the next server tick
                if (ticks < nextTick) {
                    return;
                }
                BlockPos pos = gridPos(SPLIT_NINE_TIER);
                serverAction(mc, sp -> {
                    ServerLevel level = (ServerLevel) sp.level();
                    if (level.getBlockEntity(pos) instanceof TileEntitySieve sieve) {
                        log(mc, "auto-split mode ON, row rebalanced: " + autoSplitState(sieve, level));
                        splitPrevSummary = inputSummary(sieve);
                    }
                });
                log(mc, "auto-split: client side autoSplit=" + clientAutoSplit(mc, pos));
                grab(mc, "gui_ultimate_autosplit_on.png");
                step = 4;
                nextTick = ticks + 20;
            }
            case 4 -> {   // an already even row must be byte-identical 20 ticks later (no churn)
                if (ticks < nextTick) {
                    return;
                }
                BlockPos pos = gridPos(SPLIT_NINE_TIER);
                serverAction(mc, sp -> {
                    ServerLevel level = (ServerLevel) sp.level();
                    if (level.getBlockEntity(pos) instanceof TileEntitySieve sieve) {
                        String now = inputSummary(sieve);
                        log(mc, "auto-split mode ON, 20 ticks later: " + autoSplitState(sieve, level)
                                + " identicalToPreviousSample=" + now.equals(splitPrevSummary));
                        splitPrevSummary = now;
                    }
                });
                step = 5;
                nextTick = ticks + 6;
            }
            case 5 -> {   // click 2: mode off
                if (ticks < nextTick) {
                    return;
                }
                clickToggle(mc, "click 2 -> expects OFF");
                step = 6;
                nextTick = ticks + 8;
            }
            case 6 -> {   // add material to one slot only, with the mode off
                if (ticks < nextTick) {
                    return;
                }
                BlockPos pos = gridPos(SPLIT_NINE_TIER);
                serverAction(mc, sp -> {
                    ServerLevel level = (ServerLevel) sp.level();
                    if (level.getBlockEntity(pos) instanceof TileEntitySieve sieve) {
                        addToSlot(sieve, 5, SPLIT_EXTRA);
                        log(mc, "auto-split mode OFF, " + SPLIT_EXTRA + " sand added to input slot 5 only: "
                                + autoSplitState(sieve, level));
                    }
                });
                step = 7;
                nextTick = ticks + 20;
            }
            case 7 -> {   // still lopsided 20 ticks later
                if (ticks < nextTick) {
                    return;
                }
                BlockPos pos = gridPos(SPLIT_NINE_TIER);
                serverAction(mc, sp -> {
                    ServerLevel level = (ServerLevel) sp.level();
                    if (level.getBlockEntity(pos) instanceof TileEntitySieve sieve) {
                        log(mc, "auto-split mode OFF, 20 ticks after the uneven insert: "
                                + autoSplitState(sieve, level));
                    }
                });
                step = 8;
                nextTick = ticks + 4;
            }
            case 8 -> {   // click 3: back on
                if (ticks < nextTick) {
                    return;
                }
                clickToggle(mc, "click 3 -> expects ON again");
                step = 9;
                nextTick = ticks + 8;
            }
            case 9 -> {   // rebalanced a second time, then a mesh so the machine starts consuming
                if (ticks < nextTick) {
                    return;
                }
                BlockPos pos = gridPos(SPLIT_NINE_TIER);
                serverAction(mc, sp -> {
                    ServerLevel level = (ServerLevel) sp.level();
                    if (level.getBlockEntity(pos) instanceof TileEntitySieve sieve) {
                        log(mc, "auto-split mode ON again, row rebalanced a second time: "
                                + autoSplitState(sieve, level));
                        sieve.getInventorySlots(null).get(0).setStack(new ItemStack(BuiltInRegistries.ITEM.get(
                                ResourceLocation.fromNamespaceAndPath("exdeorum", "string_mesh")), 1));
                        giveEnergy(sieve, 1_000_000L);
                        log(mc, "auto-split mode ON, string mesh inserted and energy injected: the machine now "
                                + "consumes the sand itself");
                    }
                });
                step = 10;
                nextTick = ticks + 100;
            }
            case 10, 11 -> {   // the balance has to hold while the machine sifts
                if (ticks < nextTick) {
                    return;
                }
                BlockPos pos = gridPos(SPLIT_NINE_TIER);
                serverAction(mc, sp -> {
                    ServerLevel level = (ServerLevel) sp.level();
                    if (level.getBlockEntity(pos) instanceof TileEntitySieve sieve) {
                        log(mc, "auto-split mode ON after ~" + (step == 10 ? 100 : 200)
                                + " ticks of the machine sifting: " + autoSplitState(sieve, level));
                    }
                });
                step = step == 10 ? 11 : 12;
                nextTick = ticks + 100;
            }
            case 12 -> {   // the same widget in the one-input window
                if (ticks < nextTick) {
                    return;
                }
                BlockPos pos = gridPos(SPLIT_ONE_TIER);
                placePlayerLookingAt(mc, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 4.0,
                        pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                serverAction(mc, sp -> {
                    ServerLevel level = (ServerLevel) sp.level();
                    if (!(level.getBlockEntity(pos) instanceof TileEntitySieve sieve)) {
                        log(mc, "auto-split: no machine at " + pos.toShortString());
                        return;
                    }
                    sieve.setAutoSplit(true);
                    sieve.markForSave();
                    log(mc, "auto-split layout (GUI coordinates): " + splitLayout(sieve));
                    log(mc, "auto-split setup, mode ON: " + autoSplitState(sieve, level));
                    sieve.openGui(sp);
                });
                step = 13;
                nextTick = ticks + WAIT_GUI + 6;
            }
            case 13 -> {
                if (ticks < nextTick) {
                    return;
                }
                BlockPos pos = gridPos(SPLIT_ONE_TIER);
                log(mc, "auto-split: screen=" + (mc.screen == null ? "null" : mc.screen.getClass().getName())
                        + " " + toggleRect(mc) + " client side autoSplit=" + clientAutoSplit(mc, pos));
                grab(mc, "gui_universal_autosplit_on.png");
                step = 14;
                nextTick = ticks + 6;
            }
            case 14 -> {   // the flag lives in the tile's own persistence tag
                if (ticks < nextTick) {
                    return;
                }
                BlockPos nine = gridPos(SPLIT_NINE_TIER);
                BlockPos one = gridPos(SPLIT_ONE_TIER);
                serverAction(mc, sp -> {
                    ServerLevel level = (ServerLevel) sp.level();
                    if (level.getBlockEntity(nine) instanceof TileEntitySieve sieve) {
                        log(mc, "auto-split persisted tag (9-input machine): " + autoSplitState(sieve, level));
                    }
                    if (level.getBlockEntity(one) instanceof TileEntitySieve sieve) {
                        log(mc, "auto-split persisted tag (1-input machine): " + autoSplitState(sieve, level));
                    }
                });
                if (mc.player != null) {
                    mc.player.closeContainer();
                }
                log(mc, "SPLIT PROBE DONE; HARNESS DONE, " + shots + " screenshots; leaving the game running");
                step = 15;
            }
            default -> {
                // Finished.
            }
        }
    }

    /** Clicks the toggle widget itself, the way a player does, and logs what the client did. */
    private static void clickToggle(Minecraft mc, String what) {
        ToggleButton button = toggleWidget(mc);
        boolean clicked = false;
        if (button != null && mc.screen != null) {
            clicked = mc.screen.mouseClicked(button.getX() + button.getWidth() / 2.0,
                    button.getY() + button.getHeight() / 2.0, 0);
        }
        log(mc, "auto-split " + what + ": widget click at "
                + (button == null ? "n/a" : (button.getX() + button.getWidth() / 2) + ","
                        + (button.getY() + button.getHeight() / 2))
                + " consumed=" + clicked + ", menu=" + mc.player.containerMenu.getClass().getName()
                + " id=" + mc.player.containerMenu.containerId
                + " buttonId=" + SieveContainer.BUTTON_SPLIT_INPUTS);
    }

    /** The machine's own mode flag, the same flag in its persistence tag, and the input contents. */
    private static String autoSplitState(TileEntitySieve sieve, ServerLevel level) {
        CompoundTag tag = sieve.saveWithoutMetadata(level.registryAccess());
        return "isAutoSplit=" + sieve.isAutoSplit() + " nbt.autoSplit=" + tag.getBoolean("autoSplit")
                + " " + inputSummary(sieve);
    }

    /** The client-side copy of the flag (synced with SyncableBoolean), or -1 when there is no tile. */
    private static int clientAutoSplit(Minecraft mc, BlockPos pos) {
        if (mc.level == null) {
            return -1;
        }
        return mc.level.getBlockEntity(pos) instanceof TileEntitySieve sieve ? (sieve.isAutoSplit() ? 1 : 0) : -1;
    }

    /** Empties every input slot, then parks {@code count} sand in the first one. */
    private static void parkFirstInput(TileEntitySieve sieve, int count) {
        List<mekanism.api.inventory.IInventorySlot> slots = sieve.getInventorySlots(null);
        for (int index = 1; index <= sieve.getInputSlotCount() && index < slots.size(); index++) {
            slots.get(index).setEmpty();
        }
        if (count > 0 && slots.size() > 1) {
            slots.get(1).setStack(new ItemStack(Items.SAND, count));
        }
        sieve.markForSave();
    }

    /** Adds {@code count} sand on top of what one input slot already holds. */
    private static void addToSlot(TileEntitySieve sieve, int index, int count) {
        List<mekanism.api.inventory.IInventorySlot> slots = sieve.getInventorySlots(null);
        if (index <= 0 || index >= slots.size()) {
            return;
        }
        ItemStack current = slots.get(index).getStack();
        slots.get(index).setStack(new ItemStack(Items.SAND, current.getCount() + count));
        sieve.markForSave();
    }

    /** The input slots' contents and their total, read off the live slot objects. */
    private static String inputSummary(TileEntitySieve sieve) {
        List<mekanism.api.inventory.IInventorySlot> slots = sieve.getInventorySlots(null);
        StringBuilder builder = new StringBuilder();
        int total = 0;
        for (int index = 1; index <= sieve.getInputSlotCount() && index < slots.size(); index++) {
            ItemStack stack = slots.get(index).getStack();
            builder.append('[').append(index).append(' ')
                    .append(BuiltInRegistries.ITEM.getKey(stack.getItem())).append(" x")
                    .append(stack.getCount()).append("] ");
            total += stack.getCount();
        }
        return builder.append("total=").append(total).toString();
    }

    /** Where the toggle and the things it must not cover sit, in GUI coordinates. */
    private static String splitLayout(TileEntitySieve sieve) {
        int inputs = sieve.getInputSlotCount();
        int outputs = com.mekexdeorum.MekExDeorumConfig.outputSlots();
        return "toggle=(" + SieveLayout.SPLIT_BUTTON_X + "," + SieveLayout.SPLIT_BUTTON_Y + ","
                + SieveLayout.SPLIT_BUTTON_SIZE + "x" + SieveLayout.SPLIT_BUTTON_SIZE + ") mesh=("
                + SieveLayout.MESH_X + "," + SieveLayout.MESH_Y + "," + SieveLayout.SLOT + "x"
                + SieveLayout.SLOT + ") input0=(" + SieveLayout.inputSlotX(0) + "," + SieveLayout.inputSlotY(0)
                + "," + SieveLayout.SLOT + "x" + SieveLayout.SLOT + ") input" + (inputs - 1) + "=("
                + SieveLayout.inputSlotX(inputs - 1) + "," + SieveLayout.inputSlotY(inputs - 1) + ") energy=("
                + SieveLayout.ENERGY_X + "," + SieveLayout.ENERGY_Y + "," + SieveLayout.SLOT + "x"
                + SieveLayout.SLOT + ") progress0=(" + SieveLayout.progressBarX(0) + ","
                + SieveLayout.progressBarY(0) + "," + SieveLayout.PROGRESS_WIDTH + "x"
                + SieveLayout.PROGRESS_HEIGHT + ") gui=" + SieveLayout.guiWidth(inputs, outputs) + "x"
                + SieveLayout.guiHeight(inputs, outputs) + " inputs=" + inputs + " outputs=" + outputs;
    }

    /** The switch the machine screen adds; the only toggle widget on it is the auto-split toggle. */
    private static ToggleButton toggleWidget(Minecraft mc) {
        if (mc.screen == null) {
            return null;
        }
        for (GuiEventListener child : mc.screen.children()) {
            if (child instanceof ToggleButton button) {
                return button;
            }
        }
        return null;
    }

    private static String toggleRect(Minecraft mc) {
        ToggleButton button = toggleWidget(mc);
        if (button == null) {
            return "no ToggleButton on the screen";
        }
        return "ToggleButton at screen (" + button.getX() + "," + button.getY() + ") "
                + button.getWidth() + "x" + button.getHeight() + " visible=" + button.visible;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static void placePlayerLookingAt(Minecraft mc, double eyeX, double eyeY, double eyeZ,
                                             double targetX, double targetY, double targetZ) {
        double dx = targetX - eyeX;
        double dy = targetY - eyeY;
        double dz = targetZ - eyeZ;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        placePlayer(mc, eyeX, eyeY, eyeZ, yaw, pitch);
    }

    private static void placePlayer(Minecraft mc, double eyeX, double eyeY, double eyeZ, float yaw, float pitch) {
        if (mc.player == null) {
            return;
        }
        double feetY = eyeY - mc.player.getEyeHeight();
        // Client side first, so the camera is exact on this very frame instead of easing into place.
        mc.player.setPos(eyeX, feetY, eyeZ);
        mc.player.setYRot(yaw);
        mc.player.setXRot(pitch);
        mc.player.setOldPosAndRot();
        mc.player.setDeltaMovement(Vec3.ZERO);
        if (mc.level != null && mc.level.getBlockState(mc.player.blockPosition()).isAir()) {
            // Nothing to correct; the block below the feet is only used for the log line.
        }
        serverAction(mc, sp -> {
            sp.teleportTo((ServerLevel) sp.level(), eyeX, feetY, eyeZ, yaw, pitch);
            sp.setDeltaMovement(Vec3.ZERO);
        });
    }

    private static void serverAction(Minecraft mc, java.util.function.Consumer<ServerPlayer> action) {
        if (mc.getSingleplayerServer() == null) {
            log(mc, "no integrated server, action skipped");
            return;
        }
        mc.getSingleplayerServer().execute(() -> {
            List<ServerPlayer> players = mc.getSingleplayerServer().getPlayerList().getPlayers();
            if (players.isEmpty()) {
                log(mc, "no server player, action skipped");
            } else {
                action.accept(players.get(0));
            }
        });
    }

    private static void grab(Minecraft mc, String name) {
        shots++;
        log(mc, "grab " + name + " at eye " + fmt(mc.player.getX()) + " " + fmt(mc.player.getEyeY()) + " "
                + fmt(mc.player.getZ()) + " yaw " + fmt(mc.player.getYRot()) + " pitch " + fmt(mc.player.getXRot())
                + " screen=" + (mc.screen == null ? "null" : mc.screen.getClass().getSimpleName()));
        Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(),
                component -> log(mc, "screenshot callback: " + component.getString()));
    }

    /** The numbers the screens are built from, so the shots can be checked against them. */
    private static void logLayout() {
        int outputs = com.mekexdeorum.MekExDeorumConfig.outputSlots();
        StringBuilder builder = new StringBuilder("layout: outputs=").append(outputs);
        for (int inputs : new int[] {1, 3, 5, 7, 9}) {
            builder.append("\n  inputs=").append(inputs)
                    .append(" gui=").append(SieveLayout.guiWidth(inputs, outputs)).append('x')
                    .append(SieveLayout.guiHeight(inputs, outputs))
                    .append(" columns=").append(SieveLayout.outputColumns(inputs, outputs))
                    .append(" rows=").append(SieveLayout.outputRows(inputs, outputs))
                    .append(" outputAreaLeft=").append(SieveLayout.outputAreaLeft(inputs, outputs))
                    .append(" outputAreaWidth=").append(SieveLayout.outputAreaWidth(inputs, outputs))
                    .append(" progressY=").append(SieveLayout.progressBarY(0))
                    .append(" progressHeight=").append(SieveLayout.PROGRESS_HEIGHT)
                    .append(" arrowBottom=").append(SieveLayout.PROGRESS_Y + SieveLayout.ARROW_HEIGHT)
                    .append(" outputY=").append(SieveLayout.OUTPUT_Y)
                    .append(" inventoryY=").append(SieveLayout.playerInventoryY(inputs, outputs));
        }
        log(null, builder.toString());
    }

    private static String fmt(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    private static void log(Minecraft mc, String message) {
        LOG.info("[F4 harness] {}", message);
        Minecraft instance = mc == null ? Minecraft.getInstance() : mc;
        if (instance == null) {
            return;
        }
        try {
            Path dir = new File(instance.gameDirectory, "screenshots").toPath();
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("harness.log"), "[t" + ticks + "] " + message + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception exception) {
            LOG.warn("[F4 harness] could not write the harness log", exception);
        }
    }

    private static void logThrowable(Minecraft mc, String message, Throwable throwable) {
        StringWriter writer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(writer));
        log(mc, message + System.lineSeparator() + writer);
    }
}
