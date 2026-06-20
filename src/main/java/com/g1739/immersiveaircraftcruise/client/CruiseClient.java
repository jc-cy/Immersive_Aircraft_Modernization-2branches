package com.g1739.immersiveaircraftcruise.client;

import com.g1739.immersiveaircraftcruise.ImmersiveAircraftCruise;
import com.g1739.immersiveaircraftcruise.cruise.CruiseVehicleAccess;
import com.g1739.immersiveaircraftcruise.network.CruiseNetwork;
import com.g1739.immersiveaircraftcruise.network.RequestOpenCruiseScreenPacket;
import com.g1739.immersiveaircraftcruise.network.StopCruiseNavigationPacket;
import com.g1739.immersiveaircraftcruise.network.ToggleCruiseNavigationPacket;
import com.mojang.blaze3d.platform.InputConstants;
import immersive_aircraft.client.KeyBindings;
import immersive_aircraft.entity.VehicleEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.world.entity.Entity;
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
        private static boolean brakeWasDown;
        private static boolean dismountWasDown;

        @SubscribeEvent
        public static void clientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            while (OPEN_CRUISE.consumeClick()) {
                CruiseNetwork.CHANNEL.sendToServer(openScreenPacket());
            }
            while (TOGGLE_CRUISE.consumeClick()) {
                CruiseNetwork.CHANNEL.sendToServer(togglePacket());
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
            StopCruiseNavigationPacket packet = stopPacket();
            if (packet != null) {
                CruiseNetwork.CHANNEL.sendToServer(packet);
            }
        }

        private static StopCruiseNavigationPacket stopPacket() {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player == null) {
                return null;
            }
            Entity root = minecraft.player.getRootVehicle();
            if (root instanceof VehicleEntity vehicle && vehicle instanceof CruiseVehicleAccess access) {
                if (vehicle.getControllingPassenger() == minecraft.player
                        && access.iacruise$getRoute().isEnabled()
                        && access.iacruise$getRoute().hasAnyWaypoint()) {
                    return new StopCruiseNavigationPacket(vehicle.getId(), access.iacruise$getRoute());
                }
            }
            return null;
        }
    }
}
