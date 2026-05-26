package com.g1739.immersiveaircraftcruise.network;

import com.g1739.immersiveaircraftcruise.client.ClientPacketHandlers;
import com.g1739.immersiveaircraftcruise.cruise.CruiseFuelInfo;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record UpdateCruiseFuelPacket(int entityId, CruiseFuelInfo fuelInfo) {
    public void encode(FriendlyByteBuf buffer) {
        buffer.writeInt(entityId);
        buffer.writeUtf(fuelInfo.amountText(), 32);
        buffer.writeInt(fuelInfo.remainingTicks());
        buffer.writeItem(fuelInfo.icon());
    }

    public static UpdateCruiseFuelPacket decode(FriendlyByteBuf buffer) {
        return new UpdateCruiseFuelPacket(buffer.readInt(), new CruiseFuelInfo(buffer.readUtf(32), buffer.readInt(), buffer.readItem()));
    }

    public static void handle(UpdateCruiseFuelPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientPacketHandlers.updateCruiseFuel(packet.entityId, packet.fuelInfo)));
        context.setPacketHandled(true);
    }
}
