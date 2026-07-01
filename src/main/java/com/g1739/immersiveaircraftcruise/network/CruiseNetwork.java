package com.g1739.immersiveaircraftcruise.network;

import com.g1739.immersiveaircraftcruise.ImmersiveAircraftCruise;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@EventBusSubscriber(modid = ImmersiveAircraftCruise.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class CruiseNetwork {
    private static final String PROTOCOL_VERSION = "19";

    private CruiseNetwork() {
    }

    public static void register() {
        // Registration is handled by RegisterPayloadHandlersEvent below.
    }

    public static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String path) {
        return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ImmersiveAircraftCruise.MOD_ID, path));
    }

    public static void sendToServer(CustomPacketPayload packet) {
        PacketDistributor.sendToServer(packet);
    }

    public static void sendToPlayer(net.minecraft.server.level.ServerPlayer player, CustomPacketPayload packet) {
        PacketDistributor.sendToPlayer(player, packet);
    }

    @SubscribeEvent
    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);
        registrar.playToServer(RequestOpenCruiseScreenPacket.TYPE, RequestOpenCruiseScreenPacket.STREAM_CODEC, RequestOpenCruiseScreenPacket::handle);
        registrar.playToClient(OpenCruiseScreenPacket.TYPE, OpenCruiseScreenPacket.STREAM_CODEC, OpenCruiseScreenPacket::handle);
        registrar.playToClient(UpdateCruiseRoutePacket.TYPE, UpdateCruiseRoutePacket.STREAM_CODEC, UpdateCruiseRoutePacket::handle);
        registrar.playToServer(SyncCruiseRoutePacket.TYPE, SyncCruiseRoutePacket.STREAM_CODEC, SyncCruiseRoutePacket::handle);
        registrar.playToServer(ToggleCruiseNavigationPacket.TYPE, ToggleCruiseNavigationPacket.STREAM_CODEC, ToggleCruiseNavigationPacket::handle);
        registrar.playToServer(StopCruiseNavigationPacket.TYPE, StopCruiseNavigationPacket.STREAM_CODEC, StopCruiseNavigationPacket::handle);
        registrar.playToServer(UpdateCruiseHudPacket.TYPE, UpdateCruiseHudPacket.STREAM_CODEC, UpdateCruiseHudPacket::handle);
        registrar.playToClient(UpdateCruiseFuelPacket.TYPE, UpdateCruiseFuelPacket.STREAM_CODEC, UpdateCruiseFuelPacket::handle);
        registrar.playToServer(UpdateCruiseBoostPacket.TYPE, UpdateCruiseBoostPacket.STREAM_CODEC, UpdateCruiseBoostPacket::handle);
        registrar.playToClient(SyncVehicleInventoryPacket.TYPE, SyncVehicleInventoryPacket.STREAM_CODEC, SyncVehicleInventoryPacket::handle);
    }
}
