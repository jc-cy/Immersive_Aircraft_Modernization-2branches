package com.g1739.immersiveaircraftcruise.mixin;

import immersive_aircraft.entity.VehicleEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = VehicleEntity.class, remap = false)
public interface VehicleEntityAccessor {
    /**
     * The aircraft's own stick-input smoothing steps - the value Immersive Aircraft builds
     * {@code pressingInterpolatedX/Y/Z} with. Read from the instance so an aircraft (or an addon) that
     * tunes its input response is modelled exactly instead of being assumed to use the default.
     */
    @Invoker("getInputInterpolationSteps")
    float iacruise$getInputInterpolationSteps();
}
