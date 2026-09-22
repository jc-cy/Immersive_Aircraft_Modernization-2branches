package com.g1739.immersiveaircraftcruise.mixin;

import com.g1739.immersiveaircraftcruise.ImmersiveAircraftCruise;
import com.g1739.immersiveaircraftcruise.CruiseDebug;
import com.g1739.immersiveaircraftcruise.client.CruiseClientCacheView;
import com.g1739.immersiveaircraftcruise.client.CruiseRouteCache;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Consumer;

@Mixin(ClientChunkCache.class)
public abstract class ClientChunkCacheMixin {
    @ModifyVariable(method = "updateViewRadius", at = @At("HEAD"), argsOnly = true)
    private int iacruise$keepRouteRadius(int requestedRadius) {
        return CruiseClientCacheView.routeRadiusOr(requestedRadius);
    }

    @Inject(method = "drop", at = @At("HEAD"))
    private void iacruise$reportChunkDrop(net.minecraft.world.level.ChunkPos chunkPos, CallbackInfo ci) {
        int x = chunkPos.x;
        int z = chunkPos.z;
        long hash = CruiseRouteCache.activeHash(x, z);
        if (hash != Long.MIN_VALUE) {
            CruiseDebug.debug(ImmersiveAircraftCruise.LOGGER,
                    "[CruiseChunks][Client] route chunk dropped by ClientChunkCache: x={}, z={}, hash={}, "
                            + "cacheCenter={}/{}, cacheRadius={}, loadedChunks={}",
                    x, z, hash, CruiseClientCacheView.centerX(), CruiseClientCacheView.centerZ(),
                    CruiseClientCacheView.radius(), ((ClientChunkCache) (Object) this).getLoadedChunksCount());
        }
        CruiseRouteCache.evict(x, z);
    }
    @Inject(method = "updateViewRadius", at = @At("TAIL"))
    private void iacruise$logViewRadius(int radius, CallbackInfo ci) {
        CruiseClientCacheView.setRadius(radius);
        CruiseDebug.debug(ImmersiveAircraftCruise.LOGGER,
                "[CruiseChunks][Client] cache radius applied: {}", radius);
    }

    @Inject(method = "updateViewCenter", at = @At("TAIL"))
    private void iacruise$logViewCenter(int x, int z, CallbackInfo ci) {
        CruiseClientCacheView.setCenter(x, z);
        CruiseDebug.info(ImmersiveAircraftCruise.LOGGER,
                "[CruiseChunks][Client] cache center applied: {} / {}", x, z);
    }

    @Inject(method = "replaceWithPacketData", at = @At("RETURN"))
    private void iacruise$logChunkPacket(int x, int z, FriendlyByteBuf buffer, CompoundTag heightmaps,
                                         Consumer<?> blockEntityTagOutput,
                                         CallbackInfoReturnable<LevelChunk> cir) {
        CruiseDebug.debug(ImmersiveAircraftCruise.LOGGER,
                "[CruiseChunks][Client] chunk packet: x={}, z={}, accepted={}, loadedChunks={}",
                x, z, cir.getReturnValue() != null,
                ((ClientChunkCache) (Object) this).getLoadedChunksCount());
    }
}
