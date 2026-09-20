package com.g1739.immersiveaircraftcruise.network;

import com.g1739.immersiveaircraftcruise.ImmersiveAircraftCruise;
import com.g1739.immersiveaircraftcruise.cruise.CruiseController;
import immersive_aircraft.entity.VehicleEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * The preload screen exposes the server's "slow down while the route ahead is still loading" switch. The
 * setting lives in the server config, so the value travels with the screen and the toggle comes back here.
 */
public record SetPreloadDecelerationPacket(int entityId, boolean enabled) {
    public void encode(FriendlyByteBuf buffer) {
        buffer.writeInt(entityId);
        buffer.writeBoolean(enabled);
    }

    public static SetPreloadDecelerationPacket decode(FriendlyByteBuf buffer) {
        return new SetPreloadDecelerationPacket(buffer.readInt(), buffer.readBoolean());
    }

    public static void handle(SetPreloadDecelerationPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
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
        context.setPacketHandled(true);
    }
}
