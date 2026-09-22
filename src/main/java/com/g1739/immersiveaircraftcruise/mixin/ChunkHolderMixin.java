package com.g1739.immersiveaircraftcruise.mixin;


import com.g1739.immersiveaircraftcruise.cruise.CruiseChunkSendScheduler;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.Executor;

@Mixin(ChunkHolder.class)
public abstract class ChunkHolderMixin {
    @Inject(method = "updateFutures", at = @At("TAIL"))
    private void iacruise$reapplyRoutePriority(ChunkMap chunkMap, Executor executor, CallbackInfo ci) {
        CruiseChunkSendScheduler.onChunkFuturesUpdated(chunkMap, (ChunkHolder) (Object) this);
    }
}
