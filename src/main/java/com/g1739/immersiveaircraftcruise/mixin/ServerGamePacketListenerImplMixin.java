package com.g1739.immersiveaircraftcruise.mixin;

import com.g1739.immersiveaircraftcruise.ImmersiveAircraftCruise;
import com.g1739.immersiveaircraftcruise.CruiseDebug;
import com.g1739.immersiveaircraftcruise.cruise.CruiseController;
import immersive_aircraft.entity.VehicleEntity;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps the server-side pilot and its chunk tracking center attached to a fast cruise vehicle. */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
    @Shadow
    public ServerPlayer player;

    @Inject(method = "handleMoveVehicle", at = @At("RETURN"))
    private void iacruise$positionCruiseVehiclePilot(ServerboundMoveVehiclePacket packet, CallbackInfo ci) {
        Entity root = player.getRootVehicle();
        if (!(root instanceof VehicleEntity vehicle)
                || !CruiseController.isPilot(vehicle, player)
                || !CruiseController.hasCruiseModule(vehicle)) {
            return;
        }

        double distanceBefore = player.distanceTo(vehicle);
        CruiseController.synchronizeCruiseMovement(vehicle, "move-packet");
        if (distanceBefore > 16.0d) {
            CruiseDebug.info(ImmersiveAircraftCruise.LOGGER,
                    "[CruiseChunks] corrected server pilot position: player={}, vehicleId={}, distanceBefore={}",
                    player.getScoreboardName(), vehicle.getId(), distanceBefore);
        }
    }
}
