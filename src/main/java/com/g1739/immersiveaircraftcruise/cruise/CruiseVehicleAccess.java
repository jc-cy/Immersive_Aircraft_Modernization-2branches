package com.g1739.immersiveaircraftcruise.cruise;


public interface CruiseVehicleAccess {
    CruiseRoute iacruise$getRoute();

    void iacruise$setRoute(CruiseRoute route);

    boolean iacruise$isBoosting();

    void iacruise$setBoosting(boolean boosting);
}
