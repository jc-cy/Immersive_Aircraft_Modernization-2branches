package com.g1739.immersiveaircraftcruise.network;

import com.g1739.immersiveaircraftcruise.client.ClientPacketHandlers;
import com.g1739.immersiveaircraftcruise.cruise.CruiseRoute;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record UpdateCruiseRoutePacket(int entityId, CruiseRoute route) implements CustomPacketPayload {
    public static final Type<UpdateCruiseRoutePacket> TYPE = CruiseNetwork.type("update_cruise_route");
    public static final StreamCodec<RegistryFriendlyByteBuf, UpdateCruiseRoutePacket> STREAM_CODEC = StreamCodec.ofMember(UpdateCruiseRoutePacket::encode, UpdateCruiseRoutePacket::decode);

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeInt(entityId);
        route.write(buffer);
    }

    public static UpdateCruiseRoutePacket decode(FriendlyByteBuf buffer) {
        return new UpdateCruiseRoutePacket(buffer.readInt(), CruiseRoute.read(buffer));
    }

    @Override
    public Type<UpdateCruiseRoutePacket> type() {
        return TYPE;
    }

    public static void handle(UpdateCruiseRoutePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> ClientPacketHandlers.updateCruiseRoute(packet.entityId, packet.route));
    }
}
