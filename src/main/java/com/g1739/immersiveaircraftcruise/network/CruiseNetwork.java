package com.g1739.immersiveaircraftcruise.network;

import com.g1739.immersiveaircraftcruise.ImmersiveAircraftCruise;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public final class CruiseNetwork {
    private static final String PROTOCOL_VERSION = "28";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(ImmersiveAircraftCruise.MOD_ID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private static int id;

    private CruiseNetwork() {
    }

    public static void register() {
        CHANNEL.registerMessage(id++, RequestOpenCruiseScreenPacket.class,
                RequestOpenCruiseScreenPacket::encode,
                RequestOpenCruiseScreenPacket::decode,
                RequestOpenCruiseScreenPacket::handle);
        CHANNEL.registerMessage(id++, OpenCruiseScreenPacket.class,
                OpenCruiseScreenPacket::encode,
                OpenCruiseScreenPacket::decode,
                OpenCruiseScreenPacket::handle);
        CHANNEL.registerMessage(id++, SyncCruiseRoutePacket.class,
                SyncCruiseRoutePacket::encode,
                SyncCruiseRoutePacket::decode,
                SyncCruiseRoutePacket::handle);
        CHANNEL.registerMessage(id++, ToggleCruiseNavigationPacket.class,
                ToggleCruiseNavigationPacket::encode,
                ToggleCruiseNavigationPacket::decode,
                ToggleCruiseNavigationPacket::handle);
        CHANNEL.registerMessage(id++, StopCruiseNavigationPacket.class,
                StopCruiseNavigationPacket::encode,
                StopCruiseNavigationPacket::decode,
                StopCruiseNavigationPacket::handle);
        CHANNEL.registerMessage(id++, UpdateCruiseRoutePacket.class,
                UpdateCruiseRoutePacket::encode,
                UpdateCruiseRoutePacket::decode,
                UpdateCruiseRoutePacket::handle);
        CHANNEL.registerMessage(id++, UpdateCruiseHudPacket.class,
                UpdateCruiseHudPacket::encode,
                UpdateCruiseHudPacket::decode,
                UpdateCruiseHudPacket::handle);
        CHANNEL.registerMessage(id++, UpdateCruiseFuelPacket.class,
                UpdateCruiseFuelPacket::encode,
                UpdateCruiseFuelPacket::decode,
                UpdateCruiseFuelPacket::handle);
        CHANNEL.registerMessage(id++, SyncVehicleInventoryPacket.class,
                SyncVehicleInventoryPacket::encode,
                SyncVehicleInventoryPacket::decode,
                SyncVehicleInventoryPacket::handle);
        CHANNEL.registerMessage(id++, UpdateCruiseBoostPacket.class,
                UpdateCruiseBoostPacket::encode,
                UpdateCruiseBoostPacket::decode,
                UpdateCruiseBoostPacket::handle);
        CHANNEL.registerMessage(id++, UpdateCruisePilotSpeedPacket.class,
                UpdateCruisePilotSpeedPacket::encode,
                UpdateCruisePilotSpeedPacket::decode,
                UpdateCruisePilotSpeedPacket::handle);
        CHANNEL.registerMessage(id++, UpdateCruiseAccelerationPermitPacket.class,
                UpdateCruiseAccelerationPermitPacket::encode,
                UpdateCruiseAccelerationPermitPacket::decode,
                UpdateCruiseAccelerationPermitPacket::handle);
        CHANNEL.registerMessage(id++, CruiseChunkStatePacket.class,
                CruiseChunkStatePacket::encode,
                CruiseChunkStatePacket::decode,
                CruiseChunkStatePacket::handle);
        CHANNEL.registerMessage(id++, CruiseRouteChunkPacket.class,
                CruiseRouteChunkPacket::encode,
                CruiseRouteChunkPacket::decode,
                CruiseRouteChunkPacket::handle);
        CHANNEL.registerMessage(id++, RequestCruiseRideResyncPacket.class,
                RequestCruiseRideResyncPacket::encode,
                RequestCruiseRideResyncPacket::decode,
                RequestCruiseRideResyncPacket::handle);
        CHANNEL.registerMessage(id++, SetPreloadDecelerationPacket.class,
                SetPreloadDecelerationPacket::encode,
                SetPreloadDecelerationPacket::decode,
                SetPreloadDecelerationPacket::handle);
    }
}
