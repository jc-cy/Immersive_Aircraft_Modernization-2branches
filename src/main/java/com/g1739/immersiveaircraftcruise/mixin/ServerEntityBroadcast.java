package com.g1739.immersiveaircraftcruise.mixin;

import net.minecraft.server.level.ServerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes vanilla's own tracked-entity broadcast, so a cruise aircraft can be broadcast every tick
 * instead of only when {@code ChunkMap} decides its chunk is in the entity-ticking range.
 */
@Mixin(ServerEntity.class)
public interface ServerEntityBroadcast {
    @Invoker("sendChanges")
    void iacruise$sendChanges();
}
