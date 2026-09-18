package com.g1739.immersiveaircraftcruise.network;

import com.g1739.immersiveaircraftcruise.cruise.CruiseController;
import immersive_aircraft.entity.VehicleEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record UpdateCruisePilotSpeedPacket(int entityId, float speed) {
    public void encode(FriendlyByteBuf buffer) {
        buffer.writeInt(entityId);
        buffer.writeFloat(speed);
    }

    public static UpdateCruisePilotSpeedPacket decode(FriendlyByteBuf buffer) {
        return new UpdateCruisePilotSpeedPacket(buffer.readInt(), buffer.readFloat());
    }

    public static void handle(UpdateCruisePilotSpeedPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            Entity root = player.getRootVehicle();
            if (!(root instanceof VehicleEntity vehicle)
                    || vehicle.getId() != packet.entityId
                    || !CruiseController.isPilot(vehicle, player)
                    || !CruiseController.hasCruiseModule(vehicle)) {
                return;
            }
            CruiseController.updatePilotSpeed(vehicle, packet.speed);
        });
        context.setPacketHandled(true);
    }
}
