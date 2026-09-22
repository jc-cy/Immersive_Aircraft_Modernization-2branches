package com.g1739.immersiveaircraftcruise.network;


import com.g1739.immersiveaircraftcruise.cruise.CruiseController;
import immersive_aircraft.entity.VehicleEntity;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record UpdateCruisePilotSpeedPacket(int entityId, float speed) implements CustomPacketPayload {
    public static final Type<UpdateCruisePilotSpeedPacket> TYPE = CruiseNetwork.type("update_cruise_pilot_speed");
    public static final StreamCodec<RegistryFriendlyByteBuf, UpdateCruisePilotSpeedPacket> STREAM_CODEC = StreamCodec.ofMember(UpdateCruisePilotSpeedPacket::encode, UpdateCruisePilotSpeedPacket::decode);

    public void encode(RegistryFriendlyByteBuf buffer) {
        buffer.writeInt(entityId);
        buffer.writeFloat(speed);
    }

    public static UpdateCruisePilotSpeedPacket decode(RegistryFriendlyByteBuf buffer) {
        return new UpdateCruisePilotSpeedPacket(buffer.readInt(), buffer.readFloat());
    }

    @Override
    public Type<UpdateCruisePilotSpeedPacket> type() {
        return TYPE;
    }

    public static void handle(UpdateCruisePilotSpeedPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
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
    }
}
