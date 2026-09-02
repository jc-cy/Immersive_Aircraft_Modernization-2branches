package com.g1739.immersiveaircraftcruise.mixin;

import com.g1739.immersiveaircraftcruise.cruise.CruiseChunkSendScheduler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {
    @Inject(method = "setBlockState", at = @At("TAIL"))
    private void iacruise$invalidateRoutePayload(BlockPos pos, BlockState state, boolean moved,
                                                   CallbackInfoReturnable<BlockState> callback) {
        Level level = ((LevelChunk) (Object) this).getLevel();
        if (level instanceof ServerLevel serverLevel) {
            CruiseChunkSendScheduler.invalidatePayloadCache(serverLevel,
                    ((LevelChunk) (Object) this).getPos().toLong());
        }
    }
}
