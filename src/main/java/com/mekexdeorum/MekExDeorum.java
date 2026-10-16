package com.mekexdeorum;

import com.mekexdeorum.registry.MekExDeorumBlocks;
import com.mekexdeorum.registry.MekExDeorumContainerTypes;
import com.mekexdeorum.registry.MekExDeorumCreativeTabs;
import com.mekexdeorum.registry.MekExDeorumTileEntityTypes;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MekExDeorum: a Mekanism addon for Ex Deorum.
 *
 * <p>Adds the Sieve Machine: a Mekanism machine that runs Ex Deorum's sifting recipes with any
 * mesh tier, powered by Mekanism energy, plus four factory tiers.
 */
@Mod(MekExDeorum.MOD_ID)
public final class MekExDeorum {

    public static final String MOD_ID = "mekexdeorum";
    public static final Logger LOGGER = LoggerFactory.getLogger("MekExDeorum");

    public MekExDeorum(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, MekExDeorumConfig.SPEC);

        // >>> T4: registration wiring (T1 不填，T4 只在此区块内追加)
        // One listener per deferred register. Container types come before the blocks because a
        // block type carries a GUI attribute pointing at the container type, and the tile entity
        // types come before the blocks because they are built from the block registry objects.
        MekExDeorumContainerTypes.CONTAINER_TYPES.register(modEventBus);
        MekExDeorumTileEntityTypes.TILE_ENTITY_TYPES.register(modEventBus);
        MekExDeorumBlocks.BLOCKS.register(modEventBus);
        MekExDeorumCreativeTabs.CREATIVE_TABS.register(modEventBus);
        // <<< T4: end

        LOGGER.info("MekExDeorum loaded");
    }

    /** Resource location in this mod's namespace. */
    public static ResourceLocation rl(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
