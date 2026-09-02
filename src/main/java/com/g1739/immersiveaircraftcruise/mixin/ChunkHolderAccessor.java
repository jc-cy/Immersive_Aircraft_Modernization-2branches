package com.g1739.immersiveaircraftcruise.mixin;

import net.minecraft.server.level.ChunkHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ChunkHolder.class)
public interface ChunkHolderAccessor {
    @Accessor("queueLevel")
    int iacruise$getQueueLevel();

    @Accessor("queueLevel")
    void iacruise$setQueueLevel(int queueLevel);
}
