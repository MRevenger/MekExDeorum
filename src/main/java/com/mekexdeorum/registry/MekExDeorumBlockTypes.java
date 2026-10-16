package com.mekexdeorum.registry;

import com.mekexdeorum.MekExDeorumConfig;
import com.mekexdeorum.tile.TileEntitySieve;
import com.mekexdeorum.tile.TileEntitySieveFactory;
import java.util.function.Supplier;
import mekanism.common.block.attribute.AttributeSideConfig;
import mekanism.common.block.attribute.AttributeTier;
import mekanism.common.block.attribute.AttributeUpgradeable;
import mekanism.common.content.blocktype.Machine;
import mekanism.common.content.blocktype.Machine.MachineBuilder;
import mekanism.common.registration.impl.BlockRegistryObject;
import mekanism.common.registration.impl.TileEntityTypeRegistryObject;
import mekanism.common.tier.FactoryTier;

/**
 * Block type descriptors.
 *
 * <p>Mekanism has no registry for these: a block type is a plain object handed to the block's
 * constructor and read back through the block's attributes.
 *
 * <p>The upgrade chain is mixer free. The Universal Sieve carries an {@link AttributeUpgradeable}
 * pointing at the Basic Sieve Factory, which is what the Basic Tier Installer needs, and has no
 * {@link AttributeTier} on purpose: the Basic Tier Installer has a {@code null} "from" tier and only
 * matches blocks without one, exactly as the Enrichment Chamber does. Tiers 1..3 carry their tier and
 * upgrade into the next factory; the Ultimate Sieve Factory is the top tier and has no upgrade
 * attribute at all.
 *
 * <p>Every type wires the GUI with {@code .withGui(...)}; without it the machine would have no
 * menu type and could not be opened.
 */
public class MekExDeorumBlockTypes {

    public static final Machine<TileEntitySieve> UNIVERSAL_SIEVE = MachineBuilder
            .createMachine(() -> MekExDeorumTileEntityTypes.UNIVERSAL_SIEVE, MekExDeorumLang.DESCRIPTION_UNIVERSAL_SIEVE)
            .withGui(() -> MekExDeorumContainerTypes.SIEVE)
            .withEnergyConfig(MekExDeorumConfig::energyPerItem, capacity(0))
            .with(AttributeSideConfig.ELECTRIC_MACHINE)
            .with(new AttributeUpgradeable(() -> MekExDeorumBlocks.BASIC_SIEVE_FACTORY))
            .build();

    public static final Machine<TileEntitySieveFactory> BASIC_SIEVE_FACTORY = createFactory(
            FactoryTier.BASIC,
            MekExDeorumLang.DESCRIPTION_BASIC_SIEVE_FACTORY,
            () -> MekExDeorumTileEntityTypes.BASIC_SIEVE_FACTORY,
            () -> MekExDeorumBlocks.ADVANCED_SIEVE_FACTORY);

    public static final Machine<TileEntitySieveFactory> ADVANCED_SIEVE_FACTORY = createFactory(
            FactoryTier.ADVANCED,
            MekExDeorumLang.DESCRIPTION_ADVANCED_SIEVE_FACTORY,
            () -> MekExDeorumTileEntityTypes.ADVANCED_SIEVE_FACTORY,
            () -> MekExDeorumBlocks.ELITE_SIEVE_FACTORY);

    public static final Machine<TileEntitySieveFactory> ELITE_SIEVE_FACTORY = createFactory(
            FactoryTier.ELITE,
            MekExDeorumLang.DESCRIPTION_ELITE_SIEVE_FACTORY,
            () -> MekExDeorumTileEntityTypes.ELITE_SIEVE_FACTORY,
            () -> MekExDeorumBlocks.ULTIMATE_SIEVE_FACTORY);

    /** Top tier: nothing to upgrade into, so it intentionally carries no upgrade attribute. */
    public static final Machine<TileEntitySieveFactory> ULTIMATE_SIEVE_FACTORY = createFactory(
            FactoryTier.ULTIMATE,
            MekExDeorumLang.DESCRIPTION_ULTIMATE_SIEVE_FACTORY,
            () -> MekExDeorumTileEntityTypes.ULTIMATE_SIEVE_FACTORY,
            null);

    /**
     * Energy buffer of one tier. The tier only changes the buffer size: it never touches the
     * parallelism, which comes from the installed mesh alone.
     */
    private static java.util.function.LongSupplier capacity(int tier) {
        return () -> MekExDeorumConfig.capacityForTier(tier);
    }

    private static Machine<TileEntitySieveFactory> createFactory(FactoryTier tier, MekExDeorumLang description,
            Supplier<TileEntityTypeRegistryObject<TileEntitySieveFactory>> tile,
            Supplier<BlockRegistryObject<?, ?>> upgradeTo) {
        MachineBuilder<Machine<TileEntitySieveFactory>, TileEntitySieveFactory, ?> builder = MachineBuilder
                .createMachine(tile, description)
                .withGui(() -> MekExDeorumContainerTypes.SIEVE)
                .withEnergyConfig(MekExDeorumConfig::energyPerItem, capacity(tier.ordinal() + 1))
                .with(new AttributeTier<>(tier))
                .with(AttributeSideConfig.ELECTRIC_MACHINE);
        if (upgradeTo != null) {
            builder = builder.with(new AttributeUpgradeable(upgradeTo));
        }
        return builder.build();
    }

    private MekExDeorumBlockTypes() {}
}
