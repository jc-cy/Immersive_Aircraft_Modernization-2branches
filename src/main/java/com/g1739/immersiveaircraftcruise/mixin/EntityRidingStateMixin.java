package com.g1739.immersiveaircraftcruise.mixin;

import com.g1739.immersiveaircraftcruise.cruise.CruiseChunkSendScheduler;
import com.g1739.immersiveaircraftcruise.cruise.CruiseController;
import immersive_aircraft.entity.VehicleEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Single entry point for every change of who rides a cruise aircraft.
 *
 * <p>Vanilla announces passenger-list changes only through the vehicle's tracking stream
 * ({@code ServerEntity#sendChanges}, which runs only when the vehicle's chunk is entity-ticking, and
 * only broadcasts to the players currently tracking it) and sends nothing at all from
 * {@code stopRiding}. A dismount caused by a teleport, a spectator switch, a dimension change, the
 * chunk system unloading the vehicle ({@code setRemoved} ejects all passengers) or IA's own
 * sneak-right-click kick can therefore go unnoticed by the client, leaving it riding an aircraft the
 * server no longer counts it on.
 *
 * <p>Mounts, dismounts and the "vehicle is being removed" case all leave through this mixin, which
 * pushes the authoritative passenger list straight to the affected player and records the ride so a
 * chunk unload/reload in the same dimension can restore it.
 */
@Mixin(Entity.class)
public abstract class EntityRidingStateMixin {
    @Inject(method = "removePassenger", at = @At("HEAD"))
    private void iacruise$onPassengerRemoved(Entity passenger, CallbackInfo ci) {
        if (!((Object) this instanceof VehicleEntity vehicle)
                || vehicle.level().isClientSide()
                || !(passenger instanceof ServerPlayer player)
                || !CruiseController.hasCruiseModule(vehicle)) {
            return;
        }
        if (vehicle.getRemovalReason() != null) {
            // The vehicle itself is being removed (chunk unload, destruction, dimension change), so
            // this dismount is collateral. setRemoved has already remembered the ride, and the client
            // learns about it through the entity removal packet; keeping the memory is what allows the
            // ride to be restored when the same chunk loads again.
            return;
        }
        CruiseChunkSendScheduler.onRidingStateChanged(vehicle, player, false);
    }

    /**
     * The passenger list is pushed after the change, not before it.
     *
     * <p>At the HEAD of {@code removePassenger} the departing rider is still in the vehicle's list, so a
     * packet built there would still name them as the first passenger - which is the pilot on every
     * client. Pushing it after the removal makes the remaining rider the pilot on both sides, which is
     * the hand-over a logged-out or dismounting pilot has to leave behind.
     */
    @Inject(method = "removePassenger", at = @At("RETURN"))
    private void iacruise$pushPassengerListAfterRemoval(Entity passenger, CallbackInfo ci) {
        if (!((Object) this instanceof VehicleEntity vehicle)
                || vehicle.level().isClientSide()
                || !(passenger instanceof ServerPlayer player)
                || !CruiseController.hasCruiseModule(vehicle)
                || vehicle.getRemovalReason() != null) {
            return;
        }
        CruiseChunkSendScheduler.onPassengerListChanged(vehicle, player);
    }

    @Inject(method = "startRiding(Lnet/minecraft/world/entity/Entity;Z)Z", at = @At("RETURN"))
    private void iacruise$onPassengerAdded(Entity vehicle, boolean force, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()
                && (Object) this instanceof ServerPlayer player
                && vehicle instanceof VehicleEntity aircraft
                && !aircraft.level().isClientSide()
                && CruiseController.hasCruiseModule(aircraft)) {
            CruiseChunkSendScheduler.onRidingStateChanged(aircraft, player, true);
        }
    }

    /** Records the ride before an unloaded vehicle ejects its passengers. */
    @Inject(method = "setRemoved", at = @At("HEAD"))
    private void iacruise$rememberRideBeforeRemoval(Entity.RemovalReason reason, CallbackInfo ci) {
        if ((Object) this instanceof VehicleEntity vehicle
                && !vehicle.level().isClientSide()
                && reason == Entity.RemovalReason.UNLOADED_TO_CHUNK
                && CruiseController.hasCruiseModule(vehicle)) {
            CruiseChunkSendScheduler.rememberRide(vehicle);
            // The aircraft is going away with the riders still listed on it; their clients are told so
            // explicitly, because a client that missed the tracking removal would keep riding a copy of
            // an aircraft the server can no longer correct.
            CruiseChunkSendScheduler.releaseRidersOnRemoval(vehicle);
        }
    }
}
