package com.g1739.immersiveaircraftcruise.mixin;

import com.g1739.immersiveaircraftcruise.cruise.CruiseController;
import com.g1739.immersiveaircraftcruise.cruise.CruiseModuleData;
import com.g1739.immersiveaircraftcruise.cruise.CruiseRoute;
import com.g1739.immersiveaircraftcruise.cruise.CruiseVehicleAccess;
import immersive_aircraft.entity.VehicleEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = VehicleEntity.class, remap = false)
public abstract class VehicleEntityMixin implements CruiseVehicleAccess {
    @Shadow
    protected float movementY;

    @Unique
    private CruiseRoute immersive_aircraft_cruise$route = CruiseRoute.empty();

    @Unique
    private boolean immersive_aircraft_cruise$boosting;

    @Override
    public CruiseRoute iacruise$getRoute() {
        return immersive_aircraft_cruise$route;
    }

    @Override
    public void iacruise$setRoute(CruiseRoute route) {
        immersive_aircraft_cruise$route = route == null ? CruiseRoute.empty() : route;
    }

    @Override
    public boolean iacruise$isBoosting() {
        return immersive_aircraft_cruise$boosting;
    }

    @Override
    public void iacruise$setBoosting(boolean boosting) {
        immersive_aircraft_cruise$boosting = boosting;
        VehicleEntity vehicle = (VehicleEntity) (Object) this;
        if (!vehicle.level().isClientSide()) {
            CruiseModuleData.setBoosting(vehicle, boosting);
        }
    }

    @Inject(method = {"tick", "m_8119_"}, at = @At(value = "INVOKE", target = "Limmersive_aircraft/entity/VehicleEntity;updateVelocity()V"), remap = false)
    private void immersive_aircraft_cruise$beforeControlledVelocity(CallbackInfo ci) {
        CruiseController.tick((VehicleEntity) (Object) this, movementY < -0.01f);
    }

    @Inject(method = {"tick", "m_8119_"}, at = @At("TAIL"), remap = false)
    private void immersive_aircraft_cruise$afterTick(CallbackInfo ci) {
        VehicleEntity vehicle = (VehicleEntity) (Object) this;
        CruiseController.serverProgressTick(vehicle);
        CruiseController.synchronizeCruiseMovement(vehicle, "vehicle-tick");
    }

    @Inject(method = {"tick", "m_8119_"}, at = @At(value = "INVOKE", target = "Limmersive_aircraft/entity/VehicleEntity;updateController()V"), remap = false)
    private void immersive_aircraft_cruise$beforeUpdateController(CallbackInfo ci) {
        CruiseController.beforeUpdateController((VehicleEntity) (Object) this);
    }

    @Inject(method = {"tick", "m_8119_"}, at = @At(value = "INVOKE", target = "Limmersive_aircraft/entity/VehicleEntity;updateController()V", shift = At.Shift.AFTER), remap = false)
    private void immersive_aircraft_cruise$afterUpdateController(CallbackInfo ci) {
        CruiseController.afterUpdateController((VehicleEntity) (Object) this);
    }

}
