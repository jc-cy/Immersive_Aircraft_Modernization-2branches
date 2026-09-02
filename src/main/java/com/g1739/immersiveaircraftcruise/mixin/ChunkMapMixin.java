package com.g1739.immersiveaircraftcruise.mixin;

import com.g1739.immersiveaircraftcruise.cruise.CruiseChunkSendScheduler;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.apache.commons.lang3.mutable.MutableObject;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {
    @Inject(method = "playerLoadedChunk", at = @At("HEAD"), cancellable = true)
    private void iacruise$skipDuplicateChunk(ServerPlayer player,
                                              MutableObject<ClientboundLevelChunkWithLightPacket> packet,
                                              LevelChunk chunk,
                                              CallbackInfo ci) {
        if (CruiseChunkSendScheduler.suppressDuplicateChunkSend((ChunkMap) (Object) this, player, chunk)) {
            ci.cancel();
        }
    }

    @Inject(method = "updateChunkTracking", at = @At("HEAD"), cancellable = true)
    private void iacruise$keepRouteChunkTracked(ServerPlayer player,
                                                 ChunkPos chunkPos,
                                                 MutableObject<ClientboundLevelChunkWithLightPacket> packet,
                                                 boolean wasLoaded,
                                                 boolean shouldBeLoaded,
                                                 CallbackInfo ci) {
        if (wasLoaded && !shouldBeLoaded
                && CruiseChunkSendScheduler.suppressRouteChunkUnload(
                (ChunkMap) (Object) this, player, chunkPos)) {
            ci.cancel();
        }
    }

}
