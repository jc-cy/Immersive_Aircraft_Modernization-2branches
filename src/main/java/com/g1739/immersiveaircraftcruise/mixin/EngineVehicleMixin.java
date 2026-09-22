package com.g1739.immersiveaircraftcruise.mixin;


import com.g1739.immersiveaircraftcruise.cruise.CruiseController;
import com.g1739.immersiveaircraftcruise.cruise.CruiseFuelIconAccess;
import immersive_aircraft.entity.InventoryVehicleEntity;
import immersive_aircraft.entity.EngineVehicle;
import immersive_aircraft.entity.inventory.VehicleInventoryDescription;
import immersive_aircraft.entity.inventory.slots.SlotDescription;
import immersive_aircraft.util.Utils;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = EngineVehicle.class, remap = false)
public abstract class EngineVehicleMixin implements CruiseFuelIconAccess {
    @Unique
    private ItemStack immersive_aircraft_cruise$burningFuelIcon = ItemStack.EMPTY;

    @Override
    public ItemStack iacruise$getBurningFuelIcon() {
        return immersive_aircraft_cruise$burningFuelIcon;
    }

    @Inject(method = "getEnginePower", at = @At("RETURN"), cancellable = true, remap = false)
    private void immersive_aircraft_cruise$boostPower(CallbackInfoReturnable<Float> cir) {
        cir.setReturnValue(cir.getReturnValue() * CruiseController.getPowerMultiplier((EngineVehicle) (Object) this));
    }

    @Inject(method = "getFuelConsumption", at = @At("RETURN"), cancellable = true, remap = false)
    private void immersive_aircraft_cruise$boostFuel(CallbackInfoReturnable<Float> cir) {
        cir.setReturnValue(cir.getReturnValue() * CruiseController.getFuelMultiplier((EngineVehicle) (Object) this));
    }

    @Inject(method = {"tick", "m_8119_"}, at = @At("TAIL"), remap = false)
    private void immersive_aircraft_cruise$syncFuel(CallbackInfo ci) {
        CruiseController.serverEngineTick((EngineVehicle) (Object) this);
    }

    @Inject(method = "refuel", at = @At("HEAD"), remap = false)
    private void immersive_aircraft_cruise$captureFuelIcon(CallbackInfo ci) {
        EngineVehicle vehicle = (EngineVehicle) (Object) this;
        if (vehicle.level().isClientSide()) {
            return;
        }
        InventoryVehicleEntity inventoryVehicle = vehicle;
        for (SlotDescription slot : inventoryVehicle.getInventoryDescription().getSlots(VehicleInventoryDescription.BOILER)) {
            ItemStack stack = inventoryVehicle.getInventory().getItem(slot.index());
            if (!stack.isEmpty() && Utils.getFuelTime(stack) > 0) {
                immersive_aircraft_cruise$burningFuelIcon = stack.copyWithCount(1);
                return;
            }
        }
    }
}
