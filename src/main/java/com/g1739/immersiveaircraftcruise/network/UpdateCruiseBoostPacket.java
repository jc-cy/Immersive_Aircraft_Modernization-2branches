package com.g1739.immersiveaircraftcruise.network;


import com.g1739.immersiveaircraftcruise.cruise.CruiseController;
import immersive_aircraft.entity.VehicleEntity;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record UpdateCruiseBoostPacket(int entityId, boolean boosting, float boostLevel) implements CustomPacketPayload {
    public static final Type<UpdateCruiseBoostPacket> TYPE = CruiseNetwork.type("update_cruise_boost");
    public static final StreamCodec<RegistryFriendlyByteBuf, UpdateCruiseBoostPacket> STREAM_CODEC = StreamCodec.ofMember(UpdateCruiseBoostPacket::encode, UpdateCruiseBoostPacket::decode);

    public UpdateCruiseBoostPacket {
        boostLevel = Mth.clamp(boosting ? boostLevel : 0.0f, 0.0f, 1.0f);
    }

    public void encode(RegistryFriendlyByteBuf buffer) {
        buffer.writeInt(entityId);
        buffer.writeBoolean(boosting);
        buffer.writeFloat(boostLevel);
    }

    public static UpdateCruiseBoostPacket decode(RegistryFriendlyByteBuf buffer) {
        return new UpdateCruiseBoostPacket(buffer.readInt(), buffer.readBoolean(), buffer.readFloat());
    }

    @Override
    public Type<UpdateCruiseBoostPacket> type() {
        return TYPE;
    }

    public static void handle(UpdateCruiseBoostPacket packet, IPayloadContext context) {
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
            CruiseController.updatePilotBoostingState(vehicle, packet.boosting, packet.boostLevel);
        });
    }
}
