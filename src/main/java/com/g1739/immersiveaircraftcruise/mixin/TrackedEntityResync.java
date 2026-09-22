package com.g1739.immersiveaircraftcruise.mixin;

import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Set;

/**
 * The pairing primitives of {@code ChunkMap.TrackedEntity}, exposed so the repair path can drop one
 * client into the tracked set it may have missed, and so the broadcast gap check can inspect the
 * {@code ServerEntity} that actually carries the packets.
 */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public interface TrackedEntityResync {
    @Invoker("updatePlayer")
    void iacruise$updatePlayer(ServerPlayer player);

    /**
     * Drops this player's pairing: vanilla sends the entity removal to that client and forgets it saw
     * the entity. Paired with {@link #iacruise$updatePlayer} this is the only path that makes a client
     * build a brand new copy of an entity, at the authoritative position, without needing it to tick.
     */
    @Invoker("removePlayer")
    void iacruise$removePlayer(ServerPlayer player);

    /** Who this entity is currently paired with, so a forced re-pair can be verified. */
    @Accessor("seenBy")
    Set<ServerPlayerConnection> iacruise$getSeenBy();

    @Accessor("serverEntity")
    ServerEntity iacruise$getServerEntity();
}
