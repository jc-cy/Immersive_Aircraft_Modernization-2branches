package com.g1739.immersiveaircraftcruise.mixin;

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

/** Keeps the cruise vehicle and all onboard players paired beyond normal tracking range. */
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
}
