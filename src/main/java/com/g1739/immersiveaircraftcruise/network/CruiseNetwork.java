package com.g1739.immersiveaircraftcruise.network;

import com.g1739.immersiveaircraftcruise.ImmersiveAircraftCruise;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public final class CruiseNetwork {
    private static final String PROTOCOL_VERSION = "9";
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
    }
}
