package com.mekexdeorum.registry;

import com.mekexdeorum.MekExDeorum;
import com.mekexdeorum.tile.TileEntitySieve;
import com.mekexdeorum.tile.TileEntitySieveFactory;
import mekanism.common.registration.impl.BlockRegistryObject;
import mekanism.common.registration.impl.TileEntityTypeDeferredRegister;
import mekanism.common.registration.impl.TileEntityTypeRegistryObject;
import mekanism.common.tier.FactoryTier;
import mekanism.common.tile.base.TileEntityMekanism;

/**
 * Block entity types, one per machine.
 *
 * <p>The client and server tickers are what actually drive {@code onUpdateClient} and
 * {@code onUpdateServer}; without them the machine would never run.
 */
public class MekExDeorumTileEntityTypes {

    public static final TileEntityTypeDeferredRegister TILE_ENTITY_TYPES = new TileEntityTypeDeferredRegister(MekExDeorum.MOD_ID);

    public static final TileEntityTypeRegistryObject<TileEntitySieve> UNIVERSAL_SIEVE =
            TILE_ENTITY_TYPES.mekBuilder(MekExDeorumBlocks.UNIVERSAL_SIEVE,
                            (pos, state) -> new TileEntitySieve(MekExDeorumBlocks.UNIVERSAL_SIEVE, pos, state))
                    .clientTicker(TileEntityMekanism::tickClient)
                    .serverTicker(TileEntityMekanism::tickServer)
                    .build();

    public static final TileEntityTypeRegistryObject<TileEntitySieveFactory> BASIC_SIEVE_FACTORY =
            factory(MekExDeorumBlocks.BASIC_SIEVE_FACTORY, FactoryTier.BASIC);

    public static final TileEntityTypeRegistryObject<TileEntitySieveFactory> ADVANCED_SIEVE_FACTORY =
            factory(MekExDeorumBlocks.ADVANCED_SIEVE_FACTORY, FactoryTier.ADVANCED);

    public static final TileEntityTypeRegistryObject<TileEntitySieveFactory> ELITE_SIEVE_FACTORY =
            factory(MekExDeorumBlocks.ELITE_SIEVE_FACTORY, FactoryTier.ELITE);

    public static final TileEntityTypeRegistryObject<TileEntitySieveFactory> ULTIMATE_SIEVE_FACTORY =
            factory(MekExDeorumBlocks.ULTIMATE_SIEVE_FACTORY, FactoryTier.ULTIMATE);

    private static TileEntityTypeRegistryObject<TileEntitySieveFactory> factory(
            BlockRegistryObject<?, ?> block, FactoryTier tier) {
        return TILE_ENTITY_TYPES.mekBuilder(block,
                        (pos, state) -> new TileEntitySieveFactory(block, pos, state, tier))
                .clientTicker(TileEntityMekanism::tickClient)
                .serverTicker(TileEntityMekanism::tickServer)
                .build();
    }

    private MekExDeorumTileEntityTypes() {}
}
