package com.g1739.immersiveaircraftcruise.mixin;


import com.g1739.immersiveaircraftcruise.cruise.CruiseChunkSendScheduler;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {
    /**
     * 1.21 moved chunk sending into {@code PlayerChunkSender} and dropped the old
     * {@code playerLoadedChunk}/{@code updateChunkTracking} pair this mod used to hook. The route
     * corridor is still unloaded through {@code ChunkMap#dropChunk}, which is the only remaining
     * "this player loses this chunk" exit, so the unload suppression lives there.
     */
    @Inject(method = "dropChunk", at = @At("HEAD"), cancellable = true)
    private static void iacruise$keepRouteChunkTracked(ServerPlayer player,
                                                       ChunkPos chunkPos,
                                                       CallbackInfo ci) {
        ChunkMap chunkMap = player.serverLevel().getChunkSource().chunkMap;
        if (CruiseChunkSendScheduler.suppressRouteChunkUnload(chunkMap, player, chunkPos)) {
            ci.cancel();
        }
    }
}
