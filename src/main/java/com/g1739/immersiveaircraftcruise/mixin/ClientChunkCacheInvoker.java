package com.g1739.immersiveaircraftcruise.mixin;


import net.minecraft.client.multiplayer.ClientChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ClientChunkCache.class)
public interface ClientChunkCacheInvoker {
    @Invoker("updateViewRadius")
    void iacruise$updateViewRadius(int radius);

}
