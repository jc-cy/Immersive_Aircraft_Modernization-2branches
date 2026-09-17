package com.g1739.immersiveaircraftcruise.network;

import com.g1739.immersiveaircraftcruise.client.ClientPacketHandlers;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record UpdateCruiseAccelerationPermitPacket(int entityId, boolean permitted) {
    public void encode(FriendlyByteBuf buffer) {
        buffer.writeInt(entityId);
        buffer.writeBoolean(permitted);
    }

    public static UpdateCruiseAccelerationPermitPacket decode(FriendlyByteBuf buffer) {
        return new UpdateCruiseAccelerationPermitPacket(buffer.readInt(), buffer.readBoolean());
    }

    public static void handle(UpdateCruiseAccelerationPermitPacket packet,
                               Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientPacketHandlers.updateCruiseAccelerationPermit(packet.entityId, packet.permitted)));
        context.setPacketHandled(true);
    }
}
