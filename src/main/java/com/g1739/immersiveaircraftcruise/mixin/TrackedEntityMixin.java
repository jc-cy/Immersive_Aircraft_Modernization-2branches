package com.g1739.immersiveaircraftcruise.mixin;

import com.g1739.immersiveaircraftcruise.cruise.CruiseController;
import com.g1739.immersiveaircraftcruise.cruise.CruiseVehicleAccess;
import immersive_aircraft.entity.VehicleEntity;
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

/** Keeps the piloted cruise vehicle paired with its pilot after vanilla tracking range is exceeded. */
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
        if (!(entity instanceof VehicleEntity vehicle)
                || !CruiseController.shouldKeepPilotTracked(vehicle, player)) {
            return;
        }

        if (seenBy.add(player.connection)) {
            serverEntity.addPairing(player);
        }
        ci.cancel();
    }
}
