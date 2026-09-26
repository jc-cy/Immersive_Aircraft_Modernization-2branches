package com.g1739.immersiveaircraftcruise.cruise;


public interface CruiseVehicleAccess {
    CruiseRoute iacruise$getRoute();

    void iacruise$setRoute(CruiseRoute route);

    boolean iacruise$isBoosting();

    void iacruise$setBoosting(boolean boosting);

    /**
     * Server-side world game time at which the impact protection invulnerability window ends.
     * {@code 0} means "no window armed". Runtime only, never persisted.
     */
    long iacruise$guardWindowEnd();

    void iacruise$setGuardWindowEnd(long guardWindowEnd);
}
