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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import javax.annotation.Nullable;
import mekanism.common.registration.impl.BlockRegistryObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
                log(mc, "PROBE DONE; HARNESS DONE, " + shots + " screenshots; leaving the game running");
                step = 6;
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
