package com.g1739.immersiveaircraftcruise.network;


import com.g1739.immersiveaircraftcruise.client.ClientPacketHandlers;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record UpdateCruiseAccelerationPermitPacket(int entityId, boolean permitted) implements CustomPacketPayload {
    public static final Type<UpdateCruiseAccelerationPermitPacket> TYPE = CruiseNetwork.type("update_cruise_acceleration_permit");
    public static final StreamCodec<RegistryFriendlyByteBuf, UpdateCruiseAccelerationPermitPacket> STREAM_CODEC = StreamCodec.ofMember(UpdateCruiseAccelerationPermitPacket::encode, UpdateCruiseAccelerationPermitPacket::decode);

    public void encode(RegistryFriendlyByteBuf buffer) {
        buffer.writeInt(entityId);
        buffer.writeBoolean(permitted);
    }

    public static UpdateCruiseAccelerationPermitPacket decode(RegistryFriendlyByteBuf buffer) {
        return new UpdateCruiseAccelerationPermitPacket(buffer.readInt(), buffer.readBoolean());
    }

    @Override
    public Type<UpdateCruiseAccelerationPermitPacket> type() {
        return TYPE;
    }

    public static void handle(UpdateCruiseAccelerationPermitPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> ClientPacketHandlers.updateCruiseAccelerationPermit(packet.entityId, packet.permitted));
    }
}
