package com.mekexdeorum.registry;

import com.mekexdeorum.MekExDeorum;
import mekanism.common.registration.MekanismDeferredHolder;
import mekanism.common.registration.impl.BlockRegistryObject;
import mekanism.common.registration.impl.CreativeTabDeferredRegister;
import net.minecraft.core.Holder;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;

/**
 * The mod's own creative tab, holding the five machines in tier order.
 *
 * <p>The tab is registered while the machine items are still deferred, so the icon is passed as the
 * Universal Sieve's deferred item holder: {@code DeferredHolder} is a {@link Holder} that binds
 * itself once the item registry has fired.
 */
public class MekExDeorumCreativeTabs {

    public static final CreativeTabDeferredRegister CREATIVE_TABS =
            new CreativeTabDeferredRegister(MekExDeorum.MOD_ID, MekExDeorumCreativeTabs::addToTab);

    public static final MekanismDeferredHolder<CreativeModeTab, CreativeModeTab> MEKEXDEORUM =
            CREATIVE_TABS.register("mekexdeorum", MekExDeorumLang.MEKEXDEORUM, icon(), builder -> builder);

    private static void addToTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == MEKEXDEORUM.getKey()) {
            BlockRegistryObject<?, ?>[] machines = MekExDeorumBlocks.machines().toArray(new BlockRegistryObject<?, ?>[0]);
            CreativeTabDeferredRegister.addToDisplay(event, machines);
        }
    }

    private static Holder<Item> icon() {
        return MekExDeorumBlocks.UNIVERSAL_SIEVE.getItemHolder();
    }

    private MekExDeorumCreativeTabs() {}
}
