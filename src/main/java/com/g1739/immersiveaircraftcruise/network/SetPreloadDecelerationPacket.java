package com.g1739.immersiveaircraftcruise.network;


import com.g1739.immersiveaircraftcruise.ImmersiveAircraftCruise;
import com.g1739.immersiveaircraftcruise.cruise.CruiseController;
import immersive_aircraft.entity.VehicleEntity;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * The preload screen exposes the server's "slow down while the route ahead is still loading" switch. The
 * setting lives in the server config, so the value travels with the screen and the toggle comes back here.
 */
public record SetPreloadDecelerationPacket(int entityId, boolean enabled) implements CustomPacketPayload {
    public static final Type<SetPreloadDecelerationPacket> TYPE = CruiseNetwork.type("set_preload_deceleration");
    public static final StreamCodec<RegistryFriendlyByteBuf, SetPreloadDecelerationPacket> STREAM_CODEC = StreamCodec.ofMember(SetPreloadDecelerationPacket::encode, SetPreloadDecelerationPacket::decode);

    public void encode(RegistryFriendlyByteBuf buffer) {
        buffer.writeInt(entityId);
        buffer.writeBoolean(enabled);
    }

    public static SetPreloadDecelerationPacket decode(RegistryFriendlyByteBuf buffer) {
        return new SetPreloadDecelerationPacket(buffer.readInt(), buffer.readBoolean());
    }

    @Override
    public Type<SetPreloadDecelerationPacket> type() {
        return TYPE;
    }

    public static void handle(SetPreloadDecelerationPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            Entity root = player.getRootVehicle();
            // Only the pilot of that aircraft may change a server-wide setting, which matches the screen
            // (passengers get the read-only view).
            if (!(root instanceof VehicleEntity vehicle)
                    || vehicle.getId() != packet.entityId
                    || !CruiseController.hasCruiseModule(vehicle)
                    || !CruiseController.isPilot(vehicle, player)) {
                return;
            }
            CruiseController.setPreloadAutoDeceleration(packet.enabled);
            CruiseController.updatePreloadAccelerationPermit(vehicle);
            ImmersiveAircraftCruise.LOGGER.info(
                    "[CruisePreload] auto slowdown {}: player={}, vehicleId={}",
                    packet.enabled ? "enabled" : "disabled",
                    player.getScoreboardName(), vehicle.getId());
        });
    }
}
