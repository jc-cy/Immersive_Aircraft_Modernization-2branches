package com.g1739.immersiveaircraftcruise.mixin;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTaskPriorityQueueSorter;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ChunkMap.class)
public interface ChunkMapInvoker {
    @Accessor("queueSorter")
    ChunkTaskPriorityQueueSorter iacruise$getQueueSorter();

    @Accessor("viewDistance")
    int iacruise$getViewDistance();

    @Accessor("lightEngine")
    ThreadedLevelLightEngine iacruise$getLightEngine();

    @Invoker("getVisibleChunkIfPresent")
    ChunkHolder iacruise$getVisibleChunkIfPresent(long chunkKey);

    @Invoker("getUpdatingChunkIfPresent")
    ChunkHolder iacruise$getUpdatingChunkIfPresent(long chunkKey);

}
