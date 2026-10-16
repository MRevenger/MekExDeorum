package com.mekexdeorum.tile;

import com.mekexdeorum.EjectDirection;
import com.mekexdeorum.EjectThrottle;
import com.mekexdeorum.MekExDeorumConfig;
import com.mekexdeorum.MeshTiers;
import com.mekexdeorum.SieveBatch;
import com.mekexdeorum.SieveLayout;
import com.mekexdeorum.SieveMath;
import com.mekexdeorum.compat.ExDeorumCompat;
import com.mekexdeorum.inventory.slot.OversizedOutputSlot;
import com.mekexdeorum.upgrade.SieveUpgradeData;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.DoubleSupplier;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.IContentsListener;
import mekanism.api.Upgrade;
import mekanism.api.inventory.IInventorySlot;
import mekanism.api.tier.BaseTier;
import mekanism.common.block.attribute.Attribute;
import mekanism.common.capabilities.energy.MachineEnergyContainer;
import mekanism.common.capabilities.holder.energy.EnergyContainerHelper;
import mekanism.common.capabilities.holder.energy.IEnergyContainerHolder;
import mekanism.common.capabilities.holder.slot.IInventorySlotHolder;
import mekanism.common.capabilities.holder.slot.InventorySlotHelper;
import mekanism.common.inventory.container.MekanismContainer;
import mekanism.common.inventory.slot.BasicInventorySlot;
import mekanism.common.inventory.slot.EnergyInventorySlot;
import mekanism.common.inventory.slot.InputInventorySlot;
import mekanism.common.lib.transmitter.TransmissionType;
import mekanism.common.tier.FactoryTier;
import mekanism.common.tile.component.ITileComponent;
import mekanism.common.tile.component.TileComponentEjector;
import mekanism.common.tile.component.config.ConfigInfo;
import mekanism.common.tile.component.config.DataType;
import mekanism.common.tile.component.config.slot.InventorySlotInfo;
import mekanism.common.tile.interfaces.ITierUpgradable;
import mekanism.common.tile.prefab.TileEntityConfigurableMachine;
import mekanism.common.upgrade.IUpgradeData;
import mekanism.common.util.MekanismUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * An Ex Deorum sieve driven by Mekanism energy. The mesh goes into its own EXTRA slot, siftable
 * material into the input slots and the drops into oversized output slots.
 *
 * <p>Every input slot runs its own operation, with its own progress counter and its own required
 * tick count, so a slot waiting for material never stalls a slot that has some. When a bar fills,
 * the mesh's parallelism decides how many items that slot consumes, and the whole set is resolved
 * with a single binomial batch: one draw per recipe output instead of one full sifting event per
 * input item.
 *
 * <p>The machine tier changes the number of input slots and the size of the energy buffer only; the
 * parallelism comes from the mesh alone.
 */
public class TileEntitySieve extends TileEntityConfigurableMachine implements ITierUpgradable {

    /** Ticks one operation takes before Efficiency shortens it. */
    private static final int BASE_TICKS = 100;

    private static final String NBT_PROGRESS = "progress";
    private static final String NBT_REQUIRED_TICKS = "requiredTicks";
    private static final String NBT_BATCH_SIZE = "batchSize";
    private static final String NBT_PAUSED = "paused";
    private static final String NBT_OVERFLOW = "overflow";
    private static final String NBT_OVERFLOW_ITEM = "item";
    private static final String NBT_OVERFLOW_AMOUNT = "amount";

    /**
     * Machine tier and input slot count, derived from the block while {@link #presetVariables()} runs.
     *
     * <p>No initialiser on purpose: {@code presetVariables} is called from inside the superclass
     * constructor, and an inline initialiser would run afterwards and wipe the derived value.
     */
    private int tierIndex;

    private int processes;

    /** All of these are assigned by {@link #getInitialInventory}, also inside the superclass ctor. */
    private List<IInventorySlot> inputSlots;
    private List<IInventorySlot> outputSlots;
    private BasicInventorySlot meshSlot;
    private EnergyInventorySlot energySlot;
    private MachineEnergyContainer<TileEntitySieve> energyContainer;

    /** Per input slot, so that no slot depends on any other slot's state. */
    private int[] progress;
    private int[] requiredTicks;
    private int[] batchSize;

    /** Set for a slot whose drops had to be parked; cleared once the buffer is empty again. */
    private boolean[] paused;

    /**
     * Results that did not fit into the output slots, in {@code long}. A yield larger than one slot,
     * or than all slots together, is parked here rather than truncated or dropped.
     */
    private final Map<Item, Long> overflow = new LinkedHashMap<>();

    private final EjectThrottle ejectThrottle = new EjectThrottle();

    public TileEntitySieve(Holder<Block> blockProvider, BlockPos pos, BlockState state) {
        super(blockProvider, pos, state);

        // The mesh slot is deliberately not part of the item input group: it holds a tool rather than
        // a sieve input, so it gets its own EXTRA group and the side config screen shows it apart.
        ConfigInfo itemConfig = configComponent.getConfig(TransmissionType.ITEM);
        if (itemConfig != null) {
            itemConfig.addSlotInfo(DataType.INPUT, new InventorySlotInfo(true, false, inputSlots));
            itemConfig.addSlotInfo(DataType.OUTPUT, new InventorySlotInfo(false, true, outputSlots));

            List<IInventorySlot> ioSlots = new ArrayList<>(inputSlots.size() + outputSlots.size());
            ioSlots.addAll(inputSlots);
            ioSlots.addAll(outputSlots);
            itemConfig.addSlotInfo(DataType.INPUT_OUTPUT, new InventorySlotInfo(true, true, ioSlots));

            itemConfig.addSlotInfo(DataType.EXTRA, new InventorySlotInfo(true, true, List.of(meshSlot)));
            itemConfig.addSlotInfo(DataType.ENERGY, new InventorySlotInfo(true, true, List.of(energySlot)));
        }
        configComponent.setupInputConfig(TransmissionType.ENERGY, energyContainer);

        ejectorComponent = new TileComponentEjector(this);
        ejectorComponent.setOutputData(configComponent, TransmissionType.ITEM);
        // Items are pushed by tickEjection() instead: Mekanism's own item ejection waits ten ticks
        // after every attempt, which a factory with a netherite mesh outruns many times over. The
        // component is still what owns the side configuration, the GUI tab and the serialisation.
        ejectorComponent.setCanEject(type -> type != TransmissionType.ITEM);
    }

    /**
     * Reads the machine tier off the block before anything is built.
     *
     * <p>This is the only safe place for it: {@link #getInitialInventory} and
     * {@link #getInitialEnergyContainers} both run inside the superclass constructor, at which point
     * a subclass field such as the factory's {@code FactoryTier} is still {@code null}.
     */
    @Override
    protected void presetVariables() {
        tierIndex = tierIndexFromBlock(getBlockHolder());
        processes = tierIndex <= 0 ? 1 : FactoryTier.values()[tierIndex - 1].processes;
    }

    private static int tierIndexFromBlock(Holder<Block> block) {
        BaseTier tier = Attribute.getBaseTier(block);
        if (tier == null) {
            // The plain machine carries no tier attribute on purpose: Mekanism's Basic Tier Installer
            // has a null "from" tier and only matches blocks without one.
            return 0;
        }
        return switch (tier) {
            case BASIC -> 1;
            case ADVANCED -> 2;
            case ELITE -> 3;
            case ULTIMATE -> 4;
            default -> 0;
        };
    }

    @NotNull
    @Override
    protected IInventorySlotHolder getInitialInventory(IContentsListener listener) {
        int inputs = processes;
        int outputs = MekExDeorumConfig.outputSlots();

        progress = new int[inputs];
        requiredTicks = new int[inputs];
        batchSize = new int[inputs];
        paused = new boolean[inputs];

        inputSlots = new ArrayList<>(inputs);
        outputSlots = new ArrayList<>(outputs);
        InventorySlotHelper builder = InventorySlotHelper.forSideWithConfig(this);

        // Any Ex Deorum mesh is accepted; its parallelism is read back from the item while running.
        builder.addSlot(meshSlot = BasicInventorySlot.at(ExDeorumCompat::isMesh, listener,
                SieveLayout.MESH_X, SieveLayout.MESH_Y, 1));

        for (int i = 0; i < inputs; i++) {
            // Structural validation only: recipes live in the server's recipe manager and this
            // predicate is also asked on the client while a player drags an item over the slot, so
            // it must never consult them. Whether the mesh can sift an item is decided in the tick.
            InputInventorySlot slot = InputInventorySlot.at(stack -> !stack.isEmpty(), listener,
                    SieveLayout.inputSlotX(i), SieveLayout.inputSlotY(i));
            inputSlots.add(slot);
            builder.addSlot(slot);
        }

        for (int i = 0; i < outputs; i++) {
            OversizedOutputSlot slot = OversizedOutputSlot.at(listener,
                    SieveLayout.outputSlotX(i, inputs, outputs), SieveLayout.outputSlotY(i, inputs, outputs));
            outputSlots.add(slot);
            builder.addSlot(slot);
        }

        builder.addSlot(energySlot = EnergyInventorySlot.fillOrConvert(energyContainer, this::getLevel,
                listener, SieveLayout.ENERGY_X, SieveLayout.ENERGY_Y));

        return builder.build();
    }

    @NotNull
    @Override
    protected IEnergyContainerHolder getInitialEnergyContainers(IContentsListener listener) {
        EnergyContainerHelper builder = EnergyContainerHelper.forSideWithConfig(this);
        builder.addContainer(energyContainer = MachineEnergyContainer.input(this, listener));
        // The block's energy attribute supplies the base storage, but the configured per-tier value
        // is what the machine uses: the plain machine gets exactly baseEnergyCapacity, not base * mult.
        energyContainer.setMaxEnergy(MekExDeorumConfig.capacityForTier(tierIndex));
        return builder.build();
    }

    // ------------------------------------------------------------------
    // Tick loop
    // ------------------------------------------------------------------

    @Override
    protected boolean onUpdateServer() {
        boolean sendUpdate = super.onUpdateServer();

        // Runs before anything else, so results keep flowing out of an idle machine.
        tickEjection();
        // Then the parked results get first refusal on whatever space has been freed.
        drainOverflow();

        if (!(getLevel() instanceof ServerLevel server)) {
            return sendUpdate;
        }

        boolean active = false;
        for (int index = 0; index < inputSlots.size(); index++) {
            active |= tickInputSlot(server, index);
        }
        setActive(active);
        return sendUpdate;
    }

    /**
     * One tick of one input slot. Every check - mesh, material, energy, output - is made for that
     * slot alone, which is what keeps a waiting slot from stalling a working one.
     */
    private boolean tickInputSlot(ServerLevel level, int index) {
        if (paused[index]) {
            // Its drops are parked in the overflow buffer; it resumes when the buffer empties.
            return false;
        }
        ItemStack mesh = meshSlot.getStack();
        int parallelism = getMeshParallelism();
        ItemStack input = inputSlots.get(index).getStack();

        if (parallelism <= 0 || input.isEmpty()) {
            resetSlot(index);
            return false;
        }

        if (progress[index] <= 0) {
            // Fresh bar: freeze what this operation will consume, so the reserved energy and the bar
            // itself cannot drift when the slot's contents change while the operation runs.
            if (!ExDeorumCompat.canSift(level, mesh, input)) {
                resetSlot(index);
                return false;
            }
            int planned = Math.min(parallelism, input.getCount());
            if (planned <= 0) {
                resetSlot(index);
                return false;
            }
            batchSize[index] = planned;
            requiredTicks[index] = SieveMath.requiredTicks(MekanismUtils.getTicks(this, BASE_TICKS),
                    enchantmentLevel(mesh, Enchantments.EFFICIENCY),
                    MekExDeorumConfig.efficiencySpeedBonus());
        }

        // Not enough energy for this slot's batch: hold its bar where it is. A per-slot check, so a
        // starved slot never holds up a slot that does have energy.
        long cost = SieveBatch.multiplyClamped(MekExDeorumConfig.energyPerItem(), batchSize[index]);
        if (energyContainer.getEnergy() < cost) {
            return false;
        }

        progress[index]++;
        if (progress[index] >= requiredTicks[index]) {
            return finishSlot(level, index, mesh);
        }
        markForSave();
        return true;
    }

    /**
     * Completes one slot: a single batch sift, then the results are stored.
     *
     * <p>This is the whole point of the machine. The {@code n} parallel sifting events of the
     * operation are merged into one binomial draw per recipe output, so nothing here loops over items
     * calling a full sieving event.
     */
    private boolean finishSlot(ServerLevel level, int index, ItemStack mesh) {
        IInventorySlot slot = inputSlots.get(index);
        ItemStack input = slot.getStack();
        int n = Math.min(getMeshParallelism(), input.getCount());
        if (n <= 0 || !ExDeorumCompat.canSift(level, mesh, input)) {
            resetSlot(index);
            return false;
        }

        long cost = SieveBatch.multiplyClamped(MekExDeorumConfig.energyPerItem(), n);
        if (energyContainer.getEnergy() < cost) {
            // A stronger mesh may have been inserted mid-operation; wait for the extra energy.
            return false;
        }

        List<ExDeorumCompat.Output> outputs = ExDeorumCompat.outputs(level, mesh, input);
        if (outputs.isEmpty()) {
            resetSlot(index);
            return false;
        }

        int fortune = enchantmentLevel(mesh, Enchantments.FORTUNE);
        double chanceBonus = MekExDeorumConfig.fortuneChanceBonus();
        double amountBonus = MekExDeorumConfig.fortuneAmountBonus();
        DoubleSupplier rng = level.getRandom()::nextDouble;

        // The batch: one composed draw per recipe output, accumulated in long and merged per item.
        Map<Item, Long> totals = new LinkedHashMap<>();
        for (ExDeorumCompat.Output output : outputs) {
            long produced = SieveBatch.outputFor(n, output.chance(), output.count(), fortune,
                    chanceBonus, amountBonus, rng);
            if (produced > 0L) {
                totals.merge(output.item(), produced, SieveBatch::addClamped);
            }
        }

        slot.shrinkStack(n, Action.EXECUTE);
        energyContainer.extract(cost, Action.EXECUTE, AutomationType.INTERNAL);
        damageMesh();
        resetSlot(index);

        for (Map.Entry<Item, Long> entry : totals.entrySet()) {
            long leftOver = insertResult(entry.getKey(), entry.getValue());
            if (leftOver > 0L) {
                // Never truncated and never dropped; this slot pauses until the buffer drains.
                overflow.merge(entry.getKey(), leftOver, SieveBatch::addClamped);
                paused[index] = true;
            }
        }
        markForSave();
        return true;
    }

    private void resetSlot(int index) {
        progress[index] = 0;
        requiredTicks[index] = 0;
        batchSize[index] = 0;
    }

    // ------------------------------------------------------------------
    // Output slots and the overflow buffer
    // ------------------------------------------------------------------

    /**
     * Offers an amount of one item to the output slots.
     *
     * <p>The amount is split into {@link SieveMath#MAX_CHUNK} sized pieces by
     * {@link SieveMath#forEachChunk}, so a total larger than one slot can hold still lands without
     * losing a remainder. Room is decided by each slot's own limit: an oversized slot accepts
     * {@code Integer.MAX_VALUE} items, and asking the item's max stack size instead is exactly the
     * bug that used to stall the machine the moment a slot reached 64 items.
     *
     * @return the amount that did not fit anywhere
     */
    private long insertResult(Item item, long amount) {
        if (item == null || amount <= 0L || outputSlots.isEmpty()) {
            return Math.max(0L, amount);
        }
        // [0] = still to store, [1] = stop offering: once a whole piece is refused there is no room.
        long[] state = {amount, 0L};
        SieveMath.forEachChunk(amount, chunk -> {
            if (state[1] != 0L || state[0] <= 0L) {
                return;
            }
            long offer = Math.min(state[0], chunk);
            ItemStack remaining = new ItemStack(item, (int) offer);
            for (IInventorySlot slot : outputSlots) {
                remaining = slot.insertItem(remaining, Action.EXECUTE, AutomationType.INTERNAL);
                if (remaining.isEmpty()) {
                    break;
                }
            }
            long moved = offer - remaining.getCount();
            state[0] -= moved;
            if (moved <= 0L) {
                state[1] = 1L;
            }
        });
        return state[0];
    }

    /**
     * Gives the parked results first refusal on the free space, then resumes the slots the overflow
     * had paused. Runs once per tick, before any slot advances.
     */
    private void drainOverflow() {
        if (overflow.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<Item, Long>> entries = overflow.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<Item, Long> entry = entries.next();
            long leftOver = insertResult(entry.getKey(), entry.getValue());
            if (leftOver <= 0L) {
                entries.remove();
            } else {
                entry.setValue(leftOver);
            }
        }
        unpauseIfBufferEmpty();
    }

    /** Resumes every paused input slot once nothing is parked any more. */
    private void unpauseIfBufferEmpty() {
        if (!overflow.isEmpty() || paused == null) {
            return;
        }
        boolean any = false;
        for (boolean flag : paused) {
            if (flag) {
                any = true;
                break;
            }
        }
        if (any) {
            Arrays.fill(paused, false);
            markForSave();
        }
    }

    // ------------------------------------------------------------------
    // Ejecting
    // ------------------------------------------------------------------

    /**
     * Pushes everything the machine holds into the neighbouring inventories, once per game tick.
     *
     * <p>All output slots and the overflow buffer are visited in one pass, and the sides come from the
     * config. The throttle is what reduces an unattended machine to one attempt every twenty ticks
     * once nothing has accepted anything for 200 ticks.
     */
    private void tickEjection() {
        if (getLevel() == null || getLevel().isClientSide()) {
            return;
        }
        ejectThrottle.setDynamic(MekExDeorumConfig.dynamicEject());
        if (!ejectThrottle.tick()) {
            return;
        }
        ejectThrottle.report(ejectAll());
    }

    private boolean ejectAll() {
        List<Direction> sides = ejectSides();
        if (sides.isEmpty()) {
            return false;
        }
        long moved = 0L;
        for (Direction side : sides) {
            IItemHandler handler = getLevel().getCapability(Capabilities.ItemHandler.BLOCK,
                    getBlockPos().relative(side), side.getOpposite());
            if (handler == null) {
                continue;
            }
            for (IInventorySlot slot : outputSlots) {
                moved += pushSlot(slot, handler);
            }
            moved += pushOverflow(handler);
        }
        unpauseIfBufferEmpty();
        return moved > 0L;
    }

    /** The sides to eject to, per the configured policy. */
    private List<Direction> ejectSides() {
        Direction facing = getDirection();
        EjectDirection policy = MekExDeorumConfig.ejectDirection();
        if (policy == EjectDirection.BACK) {
            return List.of(facing.getOpposite());
        }
        List<Direction> sides = new ArrayList<>(Direction.values().length);
        for (Direction side : Direction.values()) {
            if (policy == EjectDirection.ALL_SIDES_EXCEPT_FRONT && side == facing) {
                continue;
            }
            sides.add(side);
        }
        return sides;
    }

    /** Offers one output slot's whole contents to one neighbour. */
    private long pushSlot(IInventorySlot source, IItemHandler handler) {
        ItemStack stack = source.getStack();
        if (stack.isEmpty()) {
            return 0L;
        }
        ItemStack remaining = stack.copy();
        for (int i = 0; i < handler.getSlots() && !remaining.isEmpty(); i++) {
            remaining = handler.insertItem(i, remaining, false);
        }
        long moved = (long) stack.getCount() - remaining.getCount();
        if (moved > 0L) {
            if (source instanceof BasicInventorySlot basic) {
                basic.setStackUnchecked(remaining);
            } else {
                source.setStack(remaining);
            }
        }
        return moved;
    }

    /** Offers the parked results to one neighbour, at most one chunk per item per tick. */
    private long pushOverflow(IItemHandler handler) {
        long moved = 0L;
        Iterator<Map.Entry<Item, Long>> entries = overflow.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<Item, Long> entry = entries.next();
            long amount = entry.getValue();
            if (amount <= 0L) {
                entries.remove();
                continue;
            }
            ItemStack remaining = new ItemStack(entry.getKey(),
                    (int) Math.min(amount, (long) SieveMath.MAX_CHUNK));
            int offered = remaining.getCount();
            for (int i = 0; i < handler.getSlots() && !remaining.isEmpty(); i++) {
                remaining = handler.insertItem(i, remaining, false);
            }
            long pushed = (long) offered - remaining.getCount();
            if (pushed <= 0L) {
                // This neighbour is full for this item; another side still gets its turn.
                break;
            }
            long left = amount - pushed;
            if (left <= 0L) {
                entries.remove();
            } else {
                entry.setValue(left);
            }
            moved += pushed;
        }
        return moved;
    }

    // ------------------------------------------------------------------
    // Mesh
    // ------------------------------------------------------------------

    /** Items one operation consumes with the currently installed mesh, or 0 when there is none. */
    public int getMeshParallelism() {
        ItemStack mesh = meshSlot == null ? ItemStack.EMPTY : meshSlot.getStack();
        if (mesh.isEmpty()) {
            return 0;
        }
        ResourceLocation name = BuiltInRegistries.ITEM.getKey(mesh.getItem());
        return name == null ? 0 : MeshTiers.parallelismFor(name.toString());
    }

    /** Whether a mesh is installed. */
    public boolean hasMesh() {
        return meshSlot != null && !meshSlot.getStack().isEmpty();
    }

    /**
     * Reads an enchantment level off the installed mesh.
     *
     * <p>1.21 enchantments are registry driven: the holder is resolved through the level's registry
     * access, there is no static field to read.
     */
    private int enchantmentLevel(ItemStack stack, ResourceKey<Enchantment> key) {
        if (stack.isEmpty() || getLevel() == null) {
            return 0;
        }
        Optional<Holder.Reference<Enchantment>> holder = getLevel().registryAccess()
                .registry(Registries.ENCHANTMENT)
                .flatMap(registry -> registry.getHolder(key));
        return holder.map(stack::getEnchantmentLevel).orElse(0);
    }

    /**
     * Takes one point of durability per operation, if the setting is on and the mesh can take it.
     *
     * <p>Ex Deorum's six meshes are not damageable, so in practice nothing is consumed. The guard is
     * mandatory rather than cosmetic: damaging a stack that has no durability destroys it, which is
     * why this never calls {@code hurtAndBreak} at all.
     */
    private void damageMesh() {
        if (!MekExDeorumConfig.meshDamage()) {
            return;
        }
        ItemStack mesh = meshSlot.getStack();
        if (mesh.isEmpty() || !mesh.isDamageableItem()) {
            return;
        }
        ItemStack damaged = mesh.copy();
        damaged.setDamageValue(damaged.getDamageValue() + 1);
        if (damaged.getDamageValue() >= damaged.getMaxDamage()) {
            meshSlot.setEmpty();
        } else {
            meshSlot.setStack(damaged);
        }
    }

    // ------------------------------------------------------------------
    // State exposed to the menu and the screen
    // ------------------------------------------------------------------

    /** Machine tier: 0 for the plain machine, 1..4 for the factory tiers. */
    protected int getTierIndex() {
        return tierIndex;
    }

    /** Parallel operations of this machine: 1 for the plain machine, 3/5/7/9 for the factories. */
    protected int getProcesses() {
        return processes;
    }

    /** Number of input slots this machine was built with; every one of them is independent. */
    public int getInputSlotCount() {
        return getProcesses();
    }

    /** Number of output slots this machine was built with. */
    public int getOutputSlotCount() {
        return outputSlots == null ? MekExDeorumConfig.outputSlots() : outputSlots.size();
    }

    /** Progress of one input slot's bar, in the range 0.0 to 1.0. */
    public double getProgress(int inputIndex) {
        if (progress == null || inputIndex < 0 || inputIndex >= progress.length) {
            return 0.0;
        }
        int required = requiredTicks[inputIndex];
        if (required <= 0) {
            return 0.0;
        }
        return Math.min(1.0, progress[inputIndex] / (double) required);
    }

    /** The machine's energy buffer, for a GUI power bar. */
    public MachineEnergyContainer<TileEntitySieve> getEnergyContainer() {
        return energyContainer;
    }

    /** Items parked in the overflow buffer. */
    public long getOverflowAmount() {
        long total = 0L;
        for (long amount : overflow.values()) {
            total = SieveBatch.addClamped(total, amount);
        }
        return total;
    }

    @Override
    public void addContainerTrackers(MekanismContainer container) {
        super.addContainerTrackers(container);
        if (progress == null) {
            return;
        }
        // One tracked value per input slot, so every progress bar is driven by its own counter.
        container.trackArray(progress);
        container.trackArray(requiredTicks);
        container.trackArray(batchSize);
    }

    /**
     * Re-derives the processing time after a Speed upgrade, so a new speed does not only apply from
     * the following operation onwards.
     */
    @Override
    public void recalculateUpgrades(Upgrade upgrade) {
        super.recalculateUpgrades(upgrade);
        if (upgrade != Upgrade.SPEED || progress == null || meshSlot == null) {
            return;
        }
        // The Efficiency level is read from the mesh the same way it was when the bars started.
        int efficiency = enchantmentLevel(meshSlot.getStack(), Enchantments.EFFICIENCY);
        int ticks = SieveMath.requiredTicks(MekanismUtils.getTicks(this, BASE_TICKS), efficiency,
                MekExDeorumConfig.efficiencySpeedBonus());
        for (int index = 0; index < progress.length; index++) {
            if (progress[index] > 0) {
                requiredTicks[index] = ticks;
            }
        }
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    /**
     * Additional NBT written by this machine, on top of Mekanism's own slot serialisation:
     * {@code progress}, {@code requiredTicks} and {@code batchSize} (one int per input slot),
     * {@code paused} (one byte per input slot) and {@code overflow} (a list of compounds keyed
     * {@code item} and {@code amount}).
     */
    @Override
    public void saveAdditional(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider provider) {
        super.saveAdditional(tag, provider);
        int inputs = inputSlots.size();
        tag.putIntArray(NBT_PROGRESS, resize(progress, inputs));
        tag.putIntArray(NBT_REQUIRED_TICKS, resize(requiredTicks, inputs));
        tag.putIntArray(NBT_BATCH_SIZE, resize(batchSize, inputs));
        tag.putByteArray(NBT_PAUSED, packPaused(inputs));
        tag.put(NBT_OVERFLOW, saveOverflow(provider));
    }

    @Override
    public void loadAdditional(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider provider) {
        super.loadAdditional(tag, provider);
        int inputs = inputSlots.size();
        // Resized rather than assigned: the machine may have been built with a different number of
        // input slots than the one that wrote the tag.
        progress = resize(tag.getIntArray(NBT_PROGRESS), inputs);
        requiredTicks = resize(tag.getIntArray(NBT_REQUIRED_TICKS), inputs);
        batchSize = resize(tag.getIntArray(NBT_BATCH_SIZE), inputs);
        paused = unpackPaused(tag.getByteArray(NBT_PAUSED), inputs);
        loadOverflow(provider, tag.getList(NBT_OVERFLOW, Tag.TAG_COMPOUND));
    }

    private ListTag saveOverflow(HolderLookup.Provider provider) {
        ListTag list = new ListTag();
        for (Map.Entry<Item, Long> entry : overflow.entrySet()) {
            long amount = entry.getValue() == null ? 0L : entry.getValue();
            if (amount <= 0L) {
                continue;
            }
            CompoundTag element = new CompoundTag();
            // One item plus a long amount: a stack count would be clamped by the vanilla codec long
            // before the buffer's own limit is anywhere near.
            element.put(NBT_OVERFLOW_ITEM, new ItemStack(entry.getKey(), 1).save(provider));
            element.putLong(NBT_OVERFLOW_AMOUNT, amount);
            list.add(element);
        }
        return list;
    }

    private void loadOverflow(HolderLookup.Provider provider, ListTag list) {
        overflow.clear();
        for (Tag element : list) {
            if (!(element instanceof CompoundTag compound)) {
                continue;
            }
            ItemStack stack = ItemStack.parseOptional(provider, compound.getCompound(NBT_OVERFLOW_ITEM));
            long amount = compound.getLong(NBT_OVERFLOW_AMOUNT);
            if (stack.isEmpty() || amount <= 0L) {
                continue;
            }
            overflow.merge(stack.getItem(), amount, SieveBatch::addClamped);
        }
    }

    private byte[] packPaused(int inputs) {
        byte[] packed = new byte[inputs];
        for (int i = 0; i < inputs && paused != null && i < paused.length; i++) {
            packed[i] = (byte) (paused[i] ? 1 : 0);
        }
        return packed;
    }

    private static boolean[] unpackPaused(byte[] packed, int inputs) {
        boolean[] restored = new boolean[inputs];
        for (int i = 0; i < inputs && i < packed.length; i++) {
            restored[i] = packed[i] != 0;
        }
        return restored;
    }

    private static int[] resize(int[] source, int size) {
        int[] resized = new int[size];
        if (source != null) {
            System.arraycopy(source, 0, resized, 0, Math.min(source.length, size));
        }
        return resized;
    }

    // ------------------------------------------------------------------
    // Tier installer support
    // ------------------------------------------------------------------

    /**
     * Hands the new machine everything this one holds, so a tier upgrade loses nothing.
     *
     * <p>{@code canBeUpgraded()} itself is final in Mekanism's base class and answers from the
     * block's {@code AttributeUpgradeable}, which is why only the data half is implemented here.
     */
    @Nullable
    @Override
    public IUpgradeData getUpgradeData(HolderLookup.Provider provider) {
        CompoundTag components = new CompoundTag();
        for (ITileComponent component : getComponents()) {
            component.write(components, provider);
        }
        return new SieveUpgradeData(meshSlot.getStack().copy(), copyOf(inputSlots), copyOf(outputSlots),
                overflowItems(), overflowAmounts(), energySlot.getStack().copy(),
                energyContainer.getEnergy(), resize(progress, inputSlots.size()),
                resize(requiredTicks, inputSlots.size()), components);
    }

    @Override
    public void parseUpgradeData(HolderLookup.Provider provider, @NotNull IUpgradeData data) {
        if (!(data instanceof SieveUpgradeData sieve)) {
            super.parseUpgradeData(provider, data);
            return;
        }
        // Restore per role rather than per index: the new machine may have a different number of
        // input slots (one, three, five, seven or nine) and of output slots.
        restore(meshSlot, sieve.mesh);
        restoreAll(inputSlots, sieve.inputs);
        restoreAll(outputSlots, sieve.outputs);
        restore(energySlot, sieve.energyItem);
        energyContainer.setEnergy(sieve.energy);

        overflow.clear();
        for (int i = 0; i < sieve.overflowItems.size(); i++) {
            ItemStack stack = sieve.overflowItems.get(i);
            long amount = i < sieve.overflowAmounts.length ? sieve.overflowAmounts[i] : 0L;
            if (!stack.isEmpty() && amount > 0L) {
                overflow.merge(stack.getItem(), amount, SieveBatch::addClamped);
            }
        }

        progress = resize(sieve.progress, inputSlots.size());
        requiredTicks = resize(sieve.requiredTicks, inputSlots.size());
        batchSize = new int[inputSlots.size()];
        paused = new boolean[inputSlots.size()];

        for (ITileComponent component : getComponents()) {
            component.read(sieve.components, provider);
        }
        markForSave();
    }

    private List<ItemStack> overflowItems() {
        List<ItemStack> stacks = new ArrayList<>(overflow.size());
        for (Item item : overflow.keySet()) {
            stacks.add(new ItemStack(item, 1));
        }
        return stacks;
    }

    private long[] overflowAmounts() {
        long[] amounts = new long[overflow.size()];
        int index = 0;
        for (long amount : overflow.values()) {
            amounts[index++] = amount;
        }
        return amounts;
    }

    private static List<ItemStack> copyOf(List<IInventorySlot> slots) {
        List<ItemStack> contents = new ArrayList<>(slots.size());
        for (IInventorySlot slot : slots) {
            contents.add(slot.getStack().copy());
        }
        return contents;
    }

    private static void restoreAll(List<IInventorySlot> slots, List<ItemStack> contents) {
        for (int i = 0; i < slots.size(); i++) {
            restore(slots.get(i), i < contents.size() ? contents.get(i) : ItemStack.EMPTY);
        }
    }

    private static void restore(IInventorySlot slot, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            slot.setEmpty();
            return;
        }
        if (slot instanceof BasicInventorySlot basic) {
            // Restore verbatim: the item may not pass the slot's own validator (a mesh that ended up
            // in an input slot, say), but it was legitimately inside the machine before the upgrade.
            basic.setStackUnchecked(stack.copy());
        } else {
            slot.setStack(stack.copy());
        }
    }
}
