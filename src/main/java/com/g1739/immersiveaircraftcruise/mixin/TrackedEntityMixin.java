package com.g1739.immersiveaircraftcruise.mixin;


import com.g1739.immersiveaircraftcruise.cruise.CruiseChunkSendScheduler;
import com.g1739.immersiveaircraftcruise.cruise.CruiseController;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

/**
 * Keeps the cruise vehicle and all onboard players paired beyond normal tracking range, and exposes
 * the pairing primitives the repair path uses to rebuild one client's view of the aircraft.
 */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class TrackedEntityMixin {
    @Shadow
    @Final
    private Entity entity;

    @Shadow
    @Final
    private ServerEntity serverEntity;

    @Shadow
    @Final
    private Set<ServerPlayerConnection> seenBy;

    @Inject(method = "updatePlayer", at = @At("HEAD"), cancellable = true)
    private void iacruise$keepActiveCruisePilot(ServerPlayer player, CallbackInfo ci) {
        if (!CruiseController.shouldKeepCruiseEntityTracked(entity, player)) {
            return;
        }

        if (seenBy.add(player.connection)) {
            serverEntity.addPairing(player);
        }
        ci.cancel();
    }

    /** Records the aircraft state that actually goes out, for the server-side broadcast gap check. */
    @Inject(method = {"broadcast", "broadcastAndSend"}, at = @At("HEAD"))
    private void iacruise$noteBroadcast(net.minecraft.network.protocol.Packet<?> packet, CallbackInfo ci) {
        CruiseChunkSendScheduler.noteBroadcast(entity);
    }
}
