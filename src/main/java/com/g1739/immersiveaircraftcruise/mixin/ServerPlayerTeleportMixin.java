package com.g1739.immersiveaircraftcruise.mixin;


import com.g1739.immersiveaircraftcruise.cruise.CruiseChunkSendScheduler;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.RelativeMovement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Captures direct same-dimension player teleports used by teleport mods. */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerTeleportMixin {
    @Inject(method = "teleportTo(DDD)V", at = @At("HEAD"))
    private void iacruise$queueSeatedTeleport(double x, double y, double z, CallbackInfo ci) {
        CruiseChunkSendScheduler.queueSeatedTeleport((ServerPlayer) (Object) this, x, y, z);
    }

    @Inject(method = "teleportTo(DDD)V", at = @At("RETURN"))
    private void iacruise$applySeatedTeleport(double x, double y, double z, CallbackInfo ci) {
        ServerPlayer player = (ServerPlayer) (Object) this;
        CruiseChunkSendScheduler.applyPlayerTeleport(player, player.serverLevel(), x, y, z);
    }

    @Inject(method = "teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDLjava/util/Set;FF)Z", at = @At("HEAD"))
    private void iacruise$queueServerLevelTeleport(ServerLevel level, double x, double y, double z,
                                                    java.util.Set<RelativeMovement> relativeMovement,
                                                    float yaw, float pitch, CallbackInfoReturnable<Boolean> cir) {
        CruiseChunkSendScheduler.queueSeatedTeleport((ServerPlayer) (Object) this, x, y, z);
    }

    @Inject(method = "teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDLjava/util/Set;FF)Z", at = @At("RETURN"))
    private void iacruise$applyServerLevelTeleport(ServerLevel level, double x, double y, double z,
                                                    java.util.Set<RelativeMovement> relativeMovement,
                                                    float yaw, float pitch, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) {
            CruiseChunkSendScheduler.applyPlayerTeleport((ServerPlayer) (Object) this, level, x, y, z);
        }
    }

    @Inject(method = "teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDFF)V", at = @At("HEAD"))
    private void iacruise$queueDimensionTeleport(ServerLevel level, double x, double y, double z,
                                                   float yaw, float pitch, CallbackInfo ci) {
        CruiseChunkSendScheduler.queueSeatedTeleport((ServerPlayer) (Object) this, x, y, z);
    }

    @Inject(method = "teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDFF)V", at = @At("RETURN"))
    private void iacruise$applyDimensionTeleport(ServerLevel level, double x, double y, double z,
                                                   float yaw, float pitch, CallbackInfo ci) {
        CruiseChunkSendScheduler.applyPlayerTeleport((ServerPlayer) (Object) this, level, x, y, z);
    }
}
