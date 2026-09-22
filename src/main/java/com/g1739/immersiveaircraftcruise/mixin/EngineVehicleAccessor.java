package com.g1739.immersiveaircraftcruise.mixin;


import immersive_aircraft.entity.EngineVehicle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = EngineVehicle.class, remap = false)
public interface EngineVehicleAccessor {
    @Accessor("fuel")
    int[] immersive_aircraft_cruise$getFuel();
}
