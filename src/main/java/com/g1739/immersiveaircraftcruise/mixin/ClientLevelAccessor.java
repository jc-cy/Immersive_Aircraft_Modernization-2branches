package com.g1739.immersiveaircraftcruise.mixin;


import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.entity.EntityTickList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read-only view of the client's entity tick list.
 *
 * <p>The ride repair has to know whether this client still ticks an aircraft. An aircraft whose copy
 * left the client's ticked set keeps its position forever - Immersive Aircraft applies an incoming
 * position inside {@code VehicleEntity#tick()} - so the difference between "the position did not
 * arrive" and "the position arrived but nothing applies it" decides whether a repair has to move the
 * copy here or wait for the server.
 */
@Mixin(ClientLevel.class)
public interface ClientLevelAccessor {
    @Accessor("tickingEntities")
    EntityTickList iacruise$getTickingEntities();
}
