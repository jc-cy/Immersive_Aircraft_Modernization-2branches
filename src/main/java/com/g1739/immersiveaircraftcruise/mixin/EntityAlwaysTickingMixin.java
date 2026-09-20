package com.g1739.immersiveaircraftcruise.mixin;

import com.g1739.immersiveaircraftcruise.cruise.CruiseModuleData;
import immersive_aircraft.entity.VehicleEntity;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Preload navigation flies far faster than terrain can confirm an entity-ticking neighbourhood,
 * and vanilla removes an entity from {@code ServerLevel#entityTickList} the moment its section
 * moves into a chunk whose entity-ticking future has not completed yet. Adopting the same
 * exemption players use keeps an actively preloaded aircraft in the tick list for its whole flight.
 *
 * <p>Server only: the client ticks a ridden aircraft whenever its chunk is loaded, and leaving
 * client-side ticking untouched keeps the two sides from disagreeing during the join window.</p>
 */
@Mixin(Entity.class)
public abstract class EntityAlwaysTickingMixin {
    @Inject(method = "isAlwaysTicking", at = @At("HEAD"), cancellable = true)
    private void immersive_aircraft_cruise$alwaysTickPreloadAircraft(CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof VehicleEntity vehicle
                && !vehicle.level().isClientSide()
                && CruiseModuleData.usesPreloadRoute(vehicle)) {
            cir.setReturnValue(true);
        }
    }
}
