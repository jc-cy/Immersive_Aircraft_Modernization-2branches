package com.g1739.immersiveaircraftcruise.mixin;

import com.g1739.immersiveaircraftcruise.ImmersiveAircraftCruise;
import com.g1739.immersiveaircraftcruise.CruiseDebug;
import com.g1739.immersiveaircraftcruise.client.CruiseClientCacheView;
import com.g1739.immersiveaircraftcruise.client.CruiseRouteCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
    @Inject(method = "handleForgetLevelChunk", at = @At("HEAD"))
    private void iacruise$logForgetHead(ClientboundForgetLevelChunkPacket packet, CallbackInfo ci) {
        logForget(packet, "HEAD");
    }

    @Inject(method = "handleForgetLevelChunk", at = @At("RETURN"))
    private void iacruise$logForgetReturn(ClientboundForgetLevelChunkPacket packet, CallbackInfo ci) {
        logForget(packet, "RETURN");
    }

    private static void logForget(ClientboundForgetLevelChunkPacket packet, String phase) {
        if (!CruiseDebug.enabled()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        int x = packet.pos().x;
        int z = packet.pos().z;
        long activeHash = CruiseRouteCache.activeHash(x, z);
        String loadedState = "no-level";
        int loadedChunks = -1;
        String playerChunk = "none";
        String vehicleChunk = "none";
        long gameTime = -1L;
        if (minecraft.level != null) {
            LevelChunk chunk = minecraft.level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
            loadedState = chunk == null || chunk instanceof EmptyLevelChunk ? "missing" : "FULL";
            loadedChunks = minecraft.level.getChunkSource().getLoadedChunksCount();
            gameTime = minecraft.level.getGameTime();
        }
        if (minecraft.player != null) {
            playerChunk = minecraft.player.chunkPosition().toString();
            Entity root = minecraft.player.getRootVehicle();
            if (root != null) {
                vehicleChunk = new ChunkPos(root.blockPosition()).toString();
            }
        }
        String message = String.format(
                "[CruiseChunks][Client] forget phase=%s, chunk=%d/%d, activeHash=%d, "
                        + "chunkState=%s, loadedChunks=%d, cacheCenter=%d/%d, cacheRadius=%d, "
                        + "playerChunk=%s, vehicleChunk=%s, gameTime=%d, thread=%s",
                phase, x, z, activeHash, loadedState, loadedChunks,
                CruiseClientCacheView.centerX(), CruiseClientCacheView.centerZ(),
                CruiseClientCacheView.radius(), playerChunk, vehicleChunk, gameTime,
                Thread.currentThread().getName());
        if (activeHash != Long.MIN_VALUE) {
            ImmersiveAircraftCruise.LOGGER.info(message);
        } else {
            ImmersiveAircraftCruise.LOGGER.debug(message);
        }
    }
}
