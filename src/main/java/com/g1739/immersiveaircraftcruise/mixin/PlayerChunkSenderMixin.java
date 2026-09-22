package com.g1739.immersiveaircraftcruise.mixin;


import com.g1739.immersiveaircraftcruise.cruise.CruiseChunkSendScheduler;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The route corridor replaces vanilla's own chunk packet with the client-cached payload, so the
 * vanilla send for a corridor chunk is dropped here.
 *
 * <p>1.21 builds and sends the packet inside {@code PlayerChunkSender#sendChunk} instead of the old
 * {@code ChunkMap#playerLoadedChunk}, so this is the equivalent suppression point.
 */
@Mixin(PlayerChunkSender.class)
public abstract class PlayerChunkSenderMixin {
    @Inject(method = "sendChunk", at = @At("HEAD"), cancellable = true)
    private static void iacruise$skipDuplicateChunkSend(ServerGamePacketListenerImpl packetListener,
                                                        ServerLevel level,
                                                        LevelChunk chunk,
                                                        CallbackInfo ci) {
        ChunkMap chunkMap = level.getChunkSource().chunkMap;
        if (CruiseChunkSendScheduler.suppressDuplicateChunkSend(chunkMap, packetListener.player, chunk)) {
            ci.cancel();
        }
    }
}
