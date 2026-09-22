package com.g1739.immersiveaircraftcruise.network;


import com.g1739.immersiveaircraftcruise.client.ClientPacketHandlers;
import com.g1739.immersiveaircraftcruise.cruise.CruiseFuelInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record UpdateCruiseFuelPacket(int entityId, double x, double y, double z, CruiseFuelInfo fuelInfo) implements CustomPacketPayload {
    public static final Type<UpdateCruiseFuelPacket> TYPE = CruiseNetwork.type("update_cruise_fuel");
    public static final StreamCodec<RegistryFriendlyByteBuf, UpdateCruiseFuelPacket> STREAM_CODEC = StreamCodec.ofMember(UpdateCruiseFuelPacket::encode, UpdateCruiseFuelPacket::decode);

    public void encode(RegistryFriendlyByteBuf buffer) {
        buffer.writeInt(entityId);
        buffer.writeDouble(x);
        buffer.writeDouble(y);
        buffer.writeDouble(z);
        buffer.writeUtf(fuelInfo.amountText(), 32);
        buffer.writeInt(fuelInfo.remainingTicks());
        ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, fuelInfo.icon());
        buffer.writeFloat(fuelInfo.speed());
        buffer.writeBoolean(fuelInfo.boosting());
    }

    public static UpdateCruiseFuelPacket decode(RegistryFriendlyByteBuf buffer) {
        return new UpdateCruiseFuelPacket(buffer.readInt(),
                buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
                new CruiseFuelInfo(buffer.readUtf(32), buffer.readInt(),
                        ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer), buffer.readFloat(), buffer.readBoolean()));
    }

    @Override
    public Type<UpdateCruiseFuelPacket> type() {
        return TYPE;
    }

    public static void handle(UpdateCruiseFuelPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> ClientPacketHandlers.updateCruiseFuel(
                packet.entityId, packet.x, packet.y, packet.z, packet.fuelInfo));
    }
}
