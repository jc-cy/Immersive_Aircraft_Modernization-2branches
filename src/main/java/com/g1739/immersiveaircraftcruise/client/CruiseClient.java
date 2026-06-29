package com.g1739.immersiveaircraftcruise.client;

import com.g1739.immersiveaircraftcruise.ImmersiveAircraftCruise;
import com.g1739.immersiveaircraftcruise.cruise.CruiseController;
import com.g1739.immersiveaircraftcruise.cruise.CruiseRoute;
import com.g1739.immersiveaircraftcruise.cruise.CruiseVehicleAccess;
import com.g1739.immersiveaircraftcruise.network.CruiseNetwork;
import com.g1739.immersiveaircraftcruise.network.RequestOpenCruiseScreenPacket;
import com.g1739.immersiveaircraftcruise.network.StopCruiseNavigationPacket;
import com.g1739.immersiveaircraftcruise.network.ToggleCruiseNavigationPacket;
import com.mojang.blaze3d.platform.InputConstants;
import immersive_aircraft.client.KeyBindings;
import immersive_aircraft.entity.VehicleEntity;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import org.lwjgl.glfw.GLFW;

public final class CruiseClient {
    public static final KeyMapping OPEN_CRUISE = new KeyMapping(
            "key.immersive_aircraft_cruise.open_cruise",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_COMMA,
            "key.categories.immersive_aircraft_cruise"
    );
    public static final KeyMapping TOGGLE_CRUISE = new KeyMapping(
            "key.immersive_aircraft_cruise.toggle_cruise",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_PERIOD,
            "key.categories.immersive_aircraft_cruise"
    );

    private CruiseClient() {
    }

    @EventBusSubscriber(modid = ImmersiveAircraftCruise.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
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
        public static void registerOverlays(RegisterGuiLayersEvent event) {
            event.registerAbove(VanillaGuiLayers.HOTBAR,
                    ResourceLocation.fromNamespaceAndPath(ImmersiveAircraftCruise.MOD_ID, "cruise_hud"),
                    CruiseHud::render);
        }
    }

    @EventBusSubscriber(modid = ImmersiveAircraftCruise.MOD_ID, value = Dist.CLIENT)
    public static final class ForgeEvents {
        private static boolean brakeWasDown;
        private static boolean dismountWasDown;

        @SubscribeEvent
        public static void clientTick(ClientTickEvent.Post event) {
            while (OPEN_CRUISE.consumeClick()) {
                CruiseNetwork.sendToServer(openScreenPacket());
            }
            while (TOGGLE_CRUISE.consumeClick()) {
                CruiseNetwork.sendToServer(togglePacket());
            }
            boolean brakeDown = KeyBindings.down.isDown();
            boolean dismountDown = KeyBindings.dismount.isDown();
            if ((brakeDown && !brakeWasDown) || (dismountDown && !dismountWasDown)) {
                sendStopPacket();
            }
            brakeWasDown = brakeDown;
            dismountWasDown = dismountDown;
        }

        private static ToggleCruiseNavigationPacket togglePacket() {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player == null) {
                return new ToggleCruiseNavigationPacket();
            }
            Entity root = minecraft.player.getRootVehicle();
            if (root instanceof VehicleEntity vehicle && vehicle instanceof CruiseVehicleAccess access) {
                return new ToggleCruiseNavigationPacket(vehicle.getId(), access.iacruise$getRoute());
            }
            return new ToggleCruiseNavigationPacket();
        }

        private static RequestOpenCruiseScreenPacket openScreenPacket() {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player == null) {
                return new RequestOpenCruiseScreenPacket();
            }
            Entity root = minecraft.player.getRootVehicle();
            if (root instanceof VehicleEntity vehicle && vehicle instanceof CruiseVehicleAccess access) {
                return new RequestOpenCruiseScreenPacket(vehicle.getId(), access.iacruise$getRoute());
            }
            return new RequestOpenCruiseScreenPacket();
        }

        private static void sendStopPacket() {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player == null) {
                return;
            }
            Entity root = minecraft.player.getRootVehicle();
            if (root instanceof VehicleEntity vehicle && vehicle instanceof CruiseVehicleAccess access) {
                if (vehicle.getControllingPassenger() == minecraft.player) {
                    CruiseRoute localRoute = access.iacruise$getRoute();
                    CruiseNetwork.sendToServer(new StopCruiseNavigationPacket(vehicle.getId(), localRoute));
                    if (localRoute != null && localRoute.isEnabled()) {
                        stopLocalNavigation(vehicle, access, localRoute);
                    }
                }
            }
        }

        private static void stopLocalNavigation(VehicleEntity vehicle, CruiseVehicleAccess access, CruiseRoute route) {
            CruiseRoute stopped = route == null ? CruiseRoute.empty() : route.copy();
            stopped.stopNavigation();
            access.iacruise$setRoute(stopped);
            CruiseController.stopNavigationEffects(vehicle);
            CruiseController.clearCruiseInputs(vehicle);
        }
    }
}
