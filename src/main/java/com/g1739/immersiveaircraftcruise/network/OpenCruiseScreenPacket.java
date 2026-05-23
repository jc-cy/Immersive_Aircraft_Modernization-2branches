package com.g1739.immersiveaircraftcruise.network;

import com.g1739.immersiveaircraftcruise.client.ClientPacketHandlers;
import com.g1739.immersiveaircraftcruise.cruise.CruiseRoute;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record OpenCruiseScreenPacket(int entityId, RouteStorageTarget target, CruiseRoute route) {
    public void encode(FriendlyByteBuf buffer) {
        buffer.writeInt(entityId);
        target.write(buffer);
        route.write(buffer);
    }

    public static OpenCruiseScreenPacket decode(FriendlyByteBuf buffer) {
        return new OpenCruiseScreenPacket(buffer.readInt(), RouteStorageTarget.read(buffer), CruiseRoute.read(buffer));
    }

    public static void handle(OpenCruiseScreenPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientPacketHandlers.openCruiseScreen(packet.entityId, packet.target, packet.route)));
        context.setPacketHandled(true);
    }
}
