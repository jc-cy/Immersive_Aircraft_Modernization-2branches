package com.g1739.immersiveaircraftcruise.mixin;

import immersive_aircraft.entity.InventoryVehicleEntity;
import immersive_aircraft.screen.VehicleScreenHandler;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = VehicleScreenHandler.class, remap = false)
public abstract class VehicleScreenHandlerMixin {
    @Inject(method = {"stillValid", "m_6875_"}, at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void immersive_aircraft_cruise$allowMountedVehicleInventory(Player player, CallbackInfoReturnable<Boolean> cir) {
        InventoryVehicleEntity vehicle = ((VehicleScreenHandlerAccessor) this).immersive_aircraft_cruise$getVehicle();
        if (vehicle.isAlive() && player.getRootVehicle() == vehicle) {
            cir.setReturnValue(true);
        }
    }
}
