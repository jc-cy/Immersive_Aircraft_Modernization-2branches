package com.g1739.immersiveaircraftcruise.mixin;

import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * The pairing primitives of {@code ChunkMap.TrackedEntity}, exposed so the repair path can drop one
 * client into the tracked set it may have missed, and so the broadcast gap check can inspect the
 * {@code ServerEntity} that actually carries the packets.
 */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public interface TrackedEntityResync {
    @Invoker("updatePlayer")
    void iacruise$updatePlayer(ServerPlayer player);

    @Accessor("serverEntity")
    ServerEntity iacruise$getServerEntity();
}
