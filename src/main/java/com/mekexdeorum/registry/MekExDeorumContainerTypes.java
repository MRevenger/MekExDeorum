package com.mekexdeorum.registry;

import com.mekexdeorum.MekExDeorum;
import com.mekexdeorum.inventory.container.SieveContainer;
import com.mekexdeorum.tile.TileEntitySieve;
import mekanism.common.registration.impl.ContainerTypeDeferredRegister;
import mekanism.common.registration.impl.ContainerTypeRegistryObject;

/**
 * The machine's menu types.
 *
 * <p>A single type named {@code sieve} serves the Universal Sieve and every factory tier. The tile
 * class is only used to locate the block entity on the client and Mekanism's lookup matches with
 * {@code isInstance}, so registering the base {@link TileEntitySieve} also resolves the factory
 * subclasses.
 */
public class MekExDeorumContainerTypes {

    public static final ContainerTypeDeferredRegister CONTAINER_TYPES = new ContainerTypeDeferredRegister(MekExDeorum.MOD_ID);

    public static final ContainerTypeRegistryObject<SieveContainer> SIEVE =
            CONTAINER_TYPES.register("sieve", TileEntitySieve.class, SieveContainer::new);

    private MekExDeorumContainerTypes() {}
}
