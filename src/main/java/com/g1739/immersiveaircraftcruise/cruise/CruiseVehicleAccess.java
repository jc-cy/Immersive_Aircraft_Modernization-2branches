package com.g1739.immersiveaircraftcruise.cruise;

public interface CruiseVehicleAccess {
    CruiseRoute iacruise$getRoute();

    void iacruise$setRoute(CruiseRoute route);

    default void iacruise$refreshRouteFromModule() {
    }

    boolean iacruise$isBoosting();

    void iacruise$setBoosting(boolean boosting);
}
