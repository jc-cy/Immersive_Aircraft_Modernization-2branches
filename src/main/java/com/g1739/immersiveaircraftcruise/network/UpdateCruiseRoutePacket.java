package com.g1739.immersiveaircraftcruise.network;

import com.g1739.immersiveaircraftcruise.client.ClientPacketHandlers;
import com.g1739.immersiveaircraftcruise.cruise.CruiseRoute;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record UpdateCruiseRoutePacket(int entityId, CruiseRoute route) {
    public void encode(FriendlyByteBuf buffer) {
        buffer.writeInt(entityId);
        route.write(buffer);
    }

    public static UpdateCruiseRoutePacket decode(FriendlyByteBuf buffer) {
        return new UpdateCruiseRoutePacket(buffer.readInt(), CruiseRoute.read(buffer));
    }

    public static void handle(UpdateCruiseRoutePacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientPacketHandlers.updateCruiseRoute(packet.entityId, packet.route)));
        context.setPacketHandled(true);
    }
}
