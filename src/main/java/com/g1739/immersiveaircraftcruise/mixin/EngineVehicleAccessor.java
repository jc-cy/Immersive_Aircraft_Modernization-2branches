package com.g1739.immersiveaircraftcruise.mixin;


import immersive_aircraft.entity.EngineVehicle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = EngineVehicle.class, remap = false)
public interface EngineVehicleAccessor {
    @Accessor("fuel")
    int[] immersive_aircraft_cruise$getFuel();

    /**
     * The aircraft's own engine reaction speed - the value Immersive Aircraft feeds into
     * {@code enginePower.setSteps(...)}. Read from the instance so per-aircraft overrides
     * (airships, addon planes) are honoured.
     */
    @Invoker("getEngineReactionSpeed")
    float immersive_aircraft_cruise$getEngineReactionSpeed();
}
