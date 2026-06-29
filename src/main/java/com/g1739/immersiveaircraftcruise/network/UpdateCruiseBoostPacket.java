package com.g1739.immersiveaircraftcruise.network;

import com.g1739.immersiveaircraftcruise.cruise.CruiseController;
import immersive_aircraft.entity.VehicleEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record UpdateCruiseBoostPacket(int entityId, boolean boosting, float boostLevel) {
    public UpdateCruiseBoostPacket {
        boostLevel = Mth.clamp(boosting ? boostLevel : 0.0f, 0.0f, 1.0f);
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeInt(entityId);
        buffer.writeBoolean(boosting);
        buffer.writeFloat(boostLevel);
    }

    public static UpdateCruiseBoostPacket decode(FriendlyByteBuf buffer) {
        return new UpdateCruiseBoostPacket(buffer.readInt(), buffer.readBoolean(), buffer.readFloat());
    }

    public static void handle(UpdateCruiseBoostPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
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
            CruiseController.updatePilotBoostingState(vehicle, packet.boosting, packet.boostLevel);
        });
        context.setPacketHandled(true);
    }
}
