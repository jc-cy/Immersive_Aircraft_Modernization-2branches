package com.g1739.immersiveaircraftcruise.client;

import com.g1739.immersiveaircraftcruise.ImmersiveAircraftCruise;
import com.g1739.immersiveaircraftcruise.network.CruiseNetwork;
import com.g1739.immersiveaircraftcruise.network.RequestOpenCruiseScreenPacket;
import com.g1739.immersiveaircraftcruise.network.ToggleCruiseNavigationPacket;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

public final class CruiseClient {
    public static final KeyMapping OPEN_CRUISE = new KeyMapping(
            "key.immersive_aircraft_cruise.open_cruise",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_L,
            "key.categories.immersive_aircraft_cruise"
    );
    public static final KeyMapping TOGGLE_CRUISE = new KeyMapping(
            "key.immersive_aircraft_cruise.toggle_cruise",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_K,
            "key.categories.immersive_aircraft_cruise"
    );

    private CruiseClient() {
    }

    @Mod.EventBusSubscriber(modid = ImmersiveAircraftCruise.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class ModEvents {
        @SubscribeEvent
        public static void registerKeys(RegisterKeyMappingsEvent event) {
            event.register(OPEN_CRUISE);
            event.register(TOGGLE_CRUISE);
        }

        @SubscribeEvent
        public static void clientSetup(FMLClientSetupEvent event) {
            event.enqueueWork(CruiseItemProperties::register);
        }

        @SubscribeEvent
        public static void registerOverlays(RegisterGuiOverlaysEvent event) {
            event.registerAbove(VanillaGuiOverlay.HOTBAR.id(), "cruise_hud", CruiseHud::render);
        }
    }

    @Mod.EventBusSubscriber(modid = ImmersiveAircraftCruise.MOD_ID, value = Dist.CLIENT)
    public static final class ForgeEvents {
        @SubscribeEvent
        public static void clientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            while (OPEN_CRUISE.consumeClick()) {
                CruiseNetwork.CHANNEL.sendToServer(new RequestOpenCruiseScreenPacket());
            }
            while (TOGGLE_CRUISE.consumeClick()) {
                CruiseNetwork.CHANNEL.sendToServer(new ToggleCruiseNavigationPacket());
            }
        }
    }
}
