package com.g1739.immersiveaircraftcruise.network;

import com.g1739.immersiveaircraftcruise.cruise.CruiseController;
import com.g1739.immersiveaircraftcruise.cruise.CruiseRoute;
import immersive_aircraft.entity.VehicleEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

public class RequestOpenCruiseScreenPacket {
    public void encode(FriendlyByteBuf buffer) {
    }

    public static RequestOpenCruiseScreenPacket decode(FriendlyByteBuf buffer) {
        return new RequestOpenCruiseScreenPacket();
    }

    public static void handle(RequestOpenCruiseScreenPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            Entity root = player.getRootVehicle();
            if (root instanceof VehicleEntity vehicle && vehicle.hasPassenger(player)) {
                if (!CruiseController.hasCruiseModule(vehicle)) {
                    player.displayClientMessage(Component.translatable("message.immersive_aircraft_cruise.requires_module"), true);
                    return;
                }
                CruiseRoute route = CruiseController.currentRoute(vehicle);
                CruiseNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                        new OpenCruiseScreenPacket(vehicle.getId(), route.copy()));
            }
        });
        context.setPacketHandled(true);
    }
}
