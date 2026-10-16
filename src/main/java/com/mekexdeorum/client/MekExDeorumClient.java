package com.mekexdeorum.client;

import com.mekexdeorum.MekExDeorum;
import com.mekexdeorum.client.gui.GuiSieve;
import com.mekexdeorum.registry.MekExDeorumContainerTypes;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/** Client-side wiring: binds the menu type to its screen. */
@EventBusSubscriber(modid = MekExDeorum.MOD_ID, value = Dist.CLIENT)
public class MekExDeorumClient {

    @SubscribeEvent
    public static void registerScreens(RegisterMenuScreensEvent event) {
        // The Universal Sieve and all four factory tiers share one menu and one screen.
        event.register(MekExDeorumContainerTypes.SIEVE.get(), GuiSieve::new);
    }

    private MekExDeorumClient() {}
}
