package com.g1739.immersiveaircraftcruise.mixin;

import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
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

    /**
     * Vanilla's own pairing bundle (spawn packet, entity data, attributes and passenger list). Used by
     * the strong reset, which has to re-create a client's copy of the aircraft even when the tracked
     * entity's own range check would rather leave that client unpaired.
     */
    @Invoker("addPairing")
    void iacruise$addPairing(ServerPlayer player);
}
