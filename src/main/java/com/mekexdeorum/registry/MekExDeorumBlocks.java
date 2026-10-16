package com.mekexdeorum.registry;

import com.mekexdeorum.MekExDeorum;
import com.mekexdeorum.tile.TileEntitySieve;
import com.mekexdeorum.tile.TileEntitySieveFactory;
import java.util.List;
import mekanism.common.attachments.component.AttachedEjector;
import mekanism.common.attachments.component.AttachedSideConfig;
import mekanism.common.block.prefab.BlockTile;
import mekanism.common.content.blocktype.Machine;
import mekanism.common.item.block.ItemBlockTooltip;
import mekanism.common.registration.impl.BlockDeferredRegister;
import mekanism.common.registration.impl.BlockRegistryObject;
import mekanism.common.registries.MekanismDataComponents;
import mekanism.common.resource.BlockResourceInfo;
import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * The five sieve machines.
 *
 * <p>Hardness 1.5 and resistance 6.0, and deliberately <em>without</em>
 * {@code requiresCorrectToolForDrops} so a machine can be broken by hand and still drop itself.
 *
 * <p>Mekanism's {@code BlockBase} and {@code BlockTile} constructors that take a properties
 * <em>modifier</em> start from {@code Properties.of().requiresCorrectToolForDrops()}, which would
 * force the tool requirement back on. That is why a fully built {@link BlockBehaviour.Properties}
 * is passed here instead of a modifier.
 */
public class MekExDeorumBlocks {

    public static final BlockDeferredRegister BLOCKS = new BlockDeferredRegister(MekExDeorum.MOD_ID);

    private static BlockBehaviour.Properties machineProperties() {
        return BlockBehaviour.Properties.of()
                .mapColor(BlockResourceInfo.STEEL.getMapColor())
                .strength(1.5F, 6.0F);
    }

    /** Tier 0: no {@code AttributeTier}, an {@code AttributeUpgradeable} instead (see the block types). */
    public static final BlockRegistryObject<BlockTile<TileEntitySieve, Machine<TileEntitySieve>>, ItemBlockTooltip<BlockTile<TileEntitySieve, Machine<TileEntitySieve>>>> UNIVERSAL_SIEVE =
            BLOCKS.register("universal_sieve",
                    () -> new BlockTile<>(MekExDeorumBlockTypes.UNIVERSAL_SIEVE, machineProperties()),
                    (block, properties) -> new ItemBlockTooltip<>(block, true, properties
                            .component(MekanismDataComponents.EJECTOR, AttachedEjector.DEFAULT)
                            .component(MekanismDataComponents.SIDE_CONFIG, AttachedSideConfig.ELECTRIC_MACHINE)));

    public static final BlockRegistryObject<BlockTile<TileEntitySieveFactory, Machine<TileEntitySieveFactory>>, ItemBlockTooltip<BlockTile<TileEntitySieveFactory, Machine<TileEntitySieveFactory>>>> BASIC_SIEVE_FACTORY =
            factory("basic_sieve_factory", MekExDeorumBlockTypes.BASIC_SIEVE_FACTORY);

    public static final BlockRegistryObject<BlockTile<TileEntitySieveFactory, Machine<TileEntitySieveFactory>>, ItemBlockTooltip<BlockTile<TileEntitySieveFactory, Machine<TileEntitySieveFactory>>>> ADVANCED_SIEVE_FACTORY =
            factory("advanced_sieve_factory", MekExDeorumBlockTypes.ADVANCED_SIEVE_FACTORY);

    public static final BlockRegistryObject<BlockTile<TileEntitySieveFactory, Machine<TileEntitySieveFactory>>, ItemBlockTooltip<BlockTile<TileEntitySieveFactory, Machine<TileEntitySieveFactory>>>> ELITE_SIEVE_FACTORY =
            factory("elite_sieve_factory", MekExDeorumBlockTypes.ELITE_SIEVE_FACTORY);

    public static final BlockRegistryObject<BlockTile<TileEntitySieveFactory, Machine<TileEntitySieveFactory>>, ItemBlockTooltip<BlockTile<TileEntitySieveFactory, Machine<TileEntitySieveFactory>>>> ULTIMATE_SIEVE_FACTORY =
            factory("ultimate_sieve_factory", MekExDeorumBlockTypes.ULTIMATE_SIEVE_FACTORY);

    private static BlockRegistryObject<BlockTile<TileEntitySieveFactory, Machine<TileEntitySieveFactory>>, ItemBlockTooltip<BlockTile<TileEntitySieveFactory, Machine<TileEntitySieveFactory>>>> factory(
            String name, Machine<TileEntitySieveFactory> type) {
        return BLOCKS.register(name,
                () -> new BlockTile<>(type, machineProperties()),
                (block, properties) -> new ItemBlockTooltip<>(block, true, properties
                        .component(MekanismDataComponents.EJECTOR, AttachedEjector.DEFAULT)
                        .component(MekanismDataComponents.SIDE_CONFIG, AttachedSideConfig.ELECTRIC_MACHINE)));
    }

    /** The five machines in tier order, for the creative tab. */
    public static List<BlockRegistryObject<?, ?>> machines() {
        return List.of(UNIVERSAL_SIEVE, BASIC_SIEVE_FACTORY, ADVANCED_SIEVE_FACTORY, ELITE_SIEVE_FACTORY, ULTIMATE_SIEVE_FACTORY);
    }

    private MekExDeorumBlocks() {}
}
