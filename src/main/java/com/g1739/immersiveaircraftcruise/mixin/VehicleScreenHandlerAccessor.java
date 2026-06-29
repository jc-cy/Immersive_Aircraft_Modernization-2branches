package com.g1739.immersiveaircraftcruise.mixin;

import immersive_aircraft.entity.InventoryVehicleEntity;
import immersive_aircraft.screen.VehicleScreenHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = VehicleScreenHandler.class, remap = false)
public interface VehicleScreenHandlerAccessor {
    @Accessor("vehicle")
    InventoryVehicleEntity immersive_aircraft_cruise$getVehicle();
}
