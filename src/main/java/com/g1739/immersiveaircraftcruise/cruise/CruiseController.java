package com.g1739.immersiveaircraftcruise.cruise;

import com.g1739.immersiveaircraftcruise.mixin.EngineVehicleAccessor;
import com.g1739.immersiveaircraftcruise.network.CruiseNetwork;
import com.g1739.immersiveaircraftcruise.network.UpdateCruiseFuelPacket;
import com.g1739.immersiveaircraftcruise.network.UpdateCruiseRoutePacket;
import immersive_aircraft.entity.AirplaneEntity;
import immersive_aircraft.entity.EngineVehicle;
import immersive_aircraft.entity.InventoryVehicleEntity;
import immersive_aircraft.entity.Rotorcraft;
import immersive_aircraft.entity.VehicleEntity;
import immersive_aircraft.entity.inventory.VehicleInventoryDescription;
import immersive_aircraft.entity.inventory.slots.SlotDescription;
import immersive_aircraft.entity.misc.BoundingBoxDescriptor;
import immersive_aircraft.item.upgrade.VehicleStat;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;
import org.joml.Matrix3f;
import org.joml.Vector3f;

import java.util.Map;
import java.util.WeakHashMap;

public final class CruiseController {
    private static final double WAYPOINT_RADIUS = 16.0;
    private static final double FINAL_ACCELERATION_CUTOFF = 100.0;
    private static final double VERTICAL_AIRCRAFT_LANDING_HORIZONTAL_RADIUS = 1.0;
    private static final double FAST_LANDING_HORIZONTAL_RADIUS = 1.0;
    private static final double FAST_LANDING_COMPLETION_HORIZONTAL_RADIUS = 2.0;
    private static final double FAST_LANDING_TURN_RADIUS = 0.25;
    private static final double LANDING_ALTITUDE_RADIUS = 3.0;
    private static final double FAST_LANDING_PLAN_ALTITUDE_RADIUS = 1.0;
    private static final double FAST_LANDING_COMPLETION_ALTITUDE_RADIUS = 0.35;
    private static final double FAST_LANDING_POST_BRAKE_ALTITUDE_RADIUS = 1.0;
    private static final double LANDING_STOP_SPEED = 0.06;
    private static final double LANDING_VERTICAL_STOP_SPEED = 0.08;
    private static final int POST_LANDING_BRAKE_TICKS = 100;
    private static final double FAST_LANDING_DESCENT_BUFFER = 8.0;
    private static final double FAST_LANDING_BRAKE_BUFFER = 0.0;
    private static final double FAST_LANDING_BRAKE_RESPONSE_TICKS = 1.0;
    private static final int FAST_LANDING_BRAKE_SIMULATION_TICKS = 120;
    private static final double AIRPLANE_INPUT_INTERPOLATION_STEP = 0.1d;
    private static final int FAST_LANDING_FLARE_SIMULATION_TICKS = 80;
    private static final double FAST_LANDING_FLARE_VERTICAL_SPEED = 0.08;
    private static final double FAST_LANDING_BRAKE_PLATEAU_DELTA = 0.003;
    private static final int FAST_LANDING_BRAKE_PLATEAU_TICKS = 8;
    private static final double FAST_LANDING_GROUND_STOP_SPEED = 0.01;
    private static final int FAST_LANDING_DESCENT_SIMULATION_TICKS = 240;
    private static final double FAST_LANDING_SPEED_DEAD_ZONE = 0.03;
    private static final float FAST_LANDING_MIN_CRUISE_BOOST = 0.0f;
    private static final double MIN_DESCENT_RATE = 0.20;
    private static final double MIN_AIRPLANE_DESCENT_RATE = 0.32;
    private static final double MIN_ROTORCRAFT_DESCENT_RATE = 0.22;
    private static final double AIRPLANE_ACTIVE_DESCENT_SPEED_FACTOR = 0.55;
    private static final double ROTORCRAFT_ACTIVE_DESCENT_PROPERTY_FACTOR = 2.4;
    private static final double ROTORCRAFT_FAST_LANDING_BRAKE_RESERVE_TICKS = 100.0;
    private static final double ROTORCRAFT_FAST_LANDING_BRAKE_BUFFER = 1.0;
    private static final double ROTORCRAFT_FAST_LANDING_BRAKE_FACTOR = 0.88;
    private static final int ROTORCRAFT_FAST_LANDING_DISTANCE_SIMULATION_TICKS = 1200;
    private static final double ROTORCRAFT_FAST_LANDING_BACKWARD_YAW_LIMIT = 90.0;
    private static final double ROTORCRAFT_FAST_LANDING_ALTITUDE_LOOKAHEAD_TICKS = 10.0;
    private static final double ROTORCRAFT_FAST_LANDING_ALTITUDE_CONTROL_RANGE = 3.0;
    private static final double ROTORCRAFT_FAST_LANDING_ALTITUDE_RATE_DEAD_ZONE = 0.015;
    private static final int ROTORCRAFT_FAST_LANDING_VERTICAL_SIMULATION_TICKS = 1200;
    private static final int ROTORCRAFT_FAST_LANDING_VERTICAL_COAST_TICKS = 100;
    private static final double ROTORCRAFT_FAST_LANDING_COMPLETION_ALTITUDE_RADIUS = 2.0;
    private static final double FAST_LANDING_LATERAL_DEAD_ZONE = 0.5;
    private static final float FAST_LANDING_YAW_DEAD_ZONE = 0.2f;
    private static final float FAST_LANDING_MIN_TURN_INPUT = 0.08f;
    private static final float FAST_DESCENT_AIRPLANE_PITCH = 1.0f;
    private static final float FAST_DESCENT_VERTICAL_INPUT = -1.0f;
    private static final float YAW_DEAD_ZONE = 3.0f;
    private static final float YAW_REVERSE_ZONE = 175.0f;
    private static final float ALTITUDE_DEAD_ZONE = 5.0f;
    private static final float BOOST_ENTER_YAW = 3.0f;
    private static final float BOOST_EXIT_YAW = 8.0f;
    private static final float BOOST_ENTER_ALTITUDE = 5.0f;
    private static final float BOOST_EXIT_ALTITUDE = 12.0f;
    private static final double ALTITUDE_LOOKAHEAD_TICKS = 34.0;
    private static final double ALTITUDE_RATE_DEAD_ZONE = 0.025;
    private static final float ALTITUDE_CONTROL_RANGE = 44.0f;
    private static final float ALTITUDE_MAX_INPUT = 0.75f;
    private static final float ALTITUDE_SMOOTHING = 0.18f;
    private static final float BOOST_RISE_PER_TICK = 0.08f;
    private static final float BOOST_FALL_PER_TICK = 0.06f;
    private static final Map<VehicleEntity, Float> TURN_MEMORY = new WeakHashMap<>();
    private static final Map<VehicleEntity, Float> ALTITUDE_MEMORY = new WeakHashMap<>();
    private static final Map<EngineVehicle, Float> BOOST_LEVEL = new WeakHashMap<>();
    private static final Map<VehicleEntity, Vec3> CONTROLLER_VELOCITY_BEFORE = new WeakHashMap<>();
    private static final Map<VehicleEntity, ItemStack> ACTIVE_MODULE = new WeakHashMap<>();
    private static final Map<VehicleEntity, Boolean> LANDING_ACTIVE = new WeakHashMap<>();
    private static final Map<VehicleEntity, Boolean> FAST_LANDING_FINAL_BRAKE_ACTIVE = new WeakHashMap<>();
    private static final Map<VehicleEntity, Boolean> AUTO_BRAKE_INPUT = new WeakHashMap<>();
    private static final Map<VehicleEntity, Integer> POST_LANDING_BRAKE = new WeakHashMap<>();

    private CruiseController() {
    }

    public static boolean hasCruiseModule(Entity entity) {
        return entity instanceof VehicleEntity vehicle && CruiseModuleData.hasModule(vehicle);
    }

    public static void serverEngineTick(EngineVehicle engineVehicle) {
        VehicleEntity vehicle = engineVehicle;
        if (vehicle.level().isClientSide()) {
            return;
        }
        if (!CruiseModuleData.hasModule(vehicle)) {
            return;
        }
        syncFuelInfo(vehicle, engineVehicle);
    }

    public static void tick(VehicleEntity vehicle, boolean braking) {
        if (!(vehicle instanceof CruiseVehicleAccess access)) {
            return;
        }
        boolean clientSide = vehicle.level().isClientSide();
        boolean automaticBrakeInput = Boolean.TRUE.equals(AUTO_BRAKE_INPUT.remove(vehicle));

        if (!CruiseModuleData.hasModule(vehicle)) {
            ACTIVE_MODULE.remove(vehicle);
            access.iacruise$setRoute(CruiseRoute.empty());
            access.iacruise$setBoosting(false);
            if (vehicle instanceof EngineVehicle engineVehicle) {
                BOOST_LEVEL.remove(engineVehicle);
            }
            TURN_MEMORY.remove(vehicle);
            ALTITUDE_MEMORY.remove(vehicle);
            LANDING_ACTIVE.remove(vehicle);
            FAST_LANDING_FINAL_BRAKE_ACTIVE.remove(vehicle);
            AUTO_BRAKE_INPUT.remove(vehicle);
            POST_LANDING_BRAKE.remove(vehicle);
            return;
        }

        CruiseRoute route = clientSide ? access.iacruise$getRoute() : serverRoute(vehicle, access);
        if (!clientSide && vehicle.tickCount % 20 == 0) {
            syncRouteToClient(vehicle, route);
        }

        if (!route.isEnabled()) {
            stopNavigationEffects(vehicle, access);
            return;
        }

        if (braking && !automaticBrakeInput) {
            route.stopNavigation();
            stopNavigationEffects(vehicle, access);
            if (!clientSide) {
                CruiseModuleData.write(vehicle, route);
                syncRouteToClient(vehicle, route);
            }
            return;
        }

        if (route.isHoldingPattern()) {
            setBoosting(vehicle, access, false);
            tickHoldingPattern(vehicle, route);
            return;
        }

        if (!route.hasTarget()) {
            stopNavigationEffects(vehicle, access);
            return;
        }

        if (updateInitialAltitudeProgress(vehicle, route) && !clientSide) {
            CruiseModuleData.write(vehicle, route);
            syncRouteToClient(vehicle, route);
        }

        CruiseRoute.Waypoint waypoint = route.getTarget();
        Vec3 referencePosition = horizontalReferencePosition(vehicle);
        double dx = waypoint.x() + 0.5 - referencePosition.x;
        double dz = waypoint.z() + 0.5 - referencePosition.z;
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        if (tickFinalLandingApproach(vehicle, access, route, waypoint, horizontalDistance, dx, dz, clientSide)) {
            return;
        }
        if (horizontalDistance <= waypointReachRadius(vehicle, route) && canAdvanceTarget(vehicle, route)) {
            route.advance();
            if (!clientSide) {
                CruiseModuleData.write(vehicle, route);
                syncRouteToClient(vehicle, route);
            }
            waypoint = route.getTarget();
            if (waypoint == null) {
                setBoosting(vehicle, access, false);
                TURN_MEMORY.remove(vehicle);
                LANDING_ACTIVE.remove(vehicle);
                FAST_LANDING_FINAL_BRAKE_ACTIVE.remove(vehicle);
                tickHoldingPattern(vehicle, route);
                return;
            }
            referencePosition = horizontalReferencePosition(vehicle);
            dx = waypoint.x() + 0.5 - referencePosition.x;
            dz = waypoint.z() + 0.5 - referencePosition.z;
            horizontalDistance = Math.sqrt(dx * dx + dz * dz);
            if (tickFinalLandingApproach(vehicle, access, route, waypoint, horizontalDistance, dx, dz, clientSide)) {
                return;
            }
        }

        int targetAltitude = navigationAltitude(route);
        double altitudeError = targetAltitude - vehicle.getY();
        float yawError = yawError(vehicle.getYRot(), dx, dz);
        float turn = turnInput(vehicle, yawError);
        float climbInput = altitudeInput(vehicle, altitudeError);

        if (vehicle instanceof AirplaneEntity) {
            float pitchInput = -climbInput;
            setCruiseInputs(vehicle, turn, 0.0f, pitchInput);
        } else {
            setCruiseInputs(vehicle, turn, climbInput, 1.0f);
            vehicle.setYRot(vehicle.getYRot() - turn * 1.5f);
        }

        if (vehicle instanceof EngineVehicle engineVehicle && engineVehicle.getEngineTarget() < 1.0f) {
            engineVehicle.setEngineTarget(1.0f);
        }

        boolean awayFromFinal = isAwayFromFinal(vehicle, route);
        setBoosting(vehicle, access, shouldBoost(access, yawError, altitudeError, awayFromFinal));
    }

    public static void serverProgressTick(VehicleEntity vehicle) {
        if (vehicle.level().isClientSide() || !(vehicle instanceof CruiseVehicleAccess access)) {
            return;
        }
        if (!CruiseModuleData.hasModule(vehicle)) {
            return;
        }
        CruiseRoute route = serverRoute(vehicle, access);
        if (!route.isEnabled() || route.isHoldingPattern()) {
            return;
        }
        boolean changed = updateInitialAltitudeProgress(vehicle, route);
        if (advanceReachedWaypoints(vehicle, route)) {
            changed = true;
        }
        if (changed) {
            CruiseModuleData.write(vehicle, route);
            syncRouteToClient(vehicle, route);
        }
    }

    public static void stopNavigationEffects(VehicleEntity vehicle) {
        if (vehicle instanceof CruiseVehicleAccess access) {
            stopNavigationEffects(vehicle, access);
        }
    }

    public static CruiseRoute currentRoute(VehicleEntity vehicle) {
        if (!(vehicle instanceof CruiseVehicleAccess access)) {
            return CruiseModuleData.read(vehicle);
        }
        if (vehicle.level().isClientSide()) {
            return access.iacruise$getRoute();
        }
        return serverRoute(vehicle, access);
    }

    private static CruiseRoute serverRoute(VehicleEntity vehicle, CruiseVehicleAccess access) {
        return CruiseModuleData.findModule(vehicle)
                .map(stack -> {
                    CruiseRoute cached = access.iacruise$getRoute();
                    if (!cached.hasAnyWaypoint()) {
                        CruiseRoute loaded = CruiseModuleData.read(stack);
                        access.iacruise$setRoute(loaded);
                        ACTIVE_MODULE.put(vehicle, stack);
                        return loaded;
                    }
                    ACTIVE_MODULE.put(vehicle, stack);
                    return cached;
                })
                .orElseGet(() -> {
                    ACTIVE_MODULE.remove(vehicle);
                    CruiseRoute empty = CruiseRoute.empty();
                    access.iacruise$setRoute(empty);
                    return empty;
                });
    }

    public static float getPowerMultiplier(EngineVehicle vehicle) {
        if (!(vehicle instanceof CruiseVehicleAccess access)) {
            return 1.0f;
        }
        if (usesControlledBoost(vehicle)) {
            return 1.0f;
        }
        return 1.0f + boostLevel(vehicle, access);
    }

    public static float getFuelMultiplier(EngineVehicle vehicle) {
        if (!(vehicle instanceof CruiseVehicleAccess access)) {
            return 1.0f;
        }
        return 1.0f + boostLevel(vehicle, access) * 0.8f;
    }

    private static void tickHoldingPattern(VehicleEntity vehicle, CruiseRoute route) {
        if (!route.isEnabled()) {
            return;
        }
        LANDING_ACTIVE.remove(vehicle);
        FAST_LANDING_FINAL_BRAKE_ACTIVE.remove(vehicle);
        boolean rotorcraft = isVerticalAircraft(vehicle);
        float turn = rotorcraft ? 0.0f : -1.0f;
        float forward = rotorcraft ? 0.0f : 1.0f;
        CruiseRoute.Waypoint finalTarget = route.getFinalTarget();
        if (rotorcraft && finalTarget != null) {
            Vec3 referencePosition = horizontalReferencePosition(vehicle);
            double dx = finalTarget.x() + 0.5 - referencePosition.x;
            double dz = finalTarget.z() + 0.5 - referencePosition.z;
            double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
            if (horizontalDistance > VERTICAL_AIRCRAFT_LANDING_HORIZONTAL_RADIUS) {
                float yawError = yawError(vehicle.getYRot(), dx, dz);
                turn = fastLandingTurnInput(vehicle, yawError, horizontalDistance);
                forward = 0.4f;
            }
        }
        double altitudeError = route.getFinalAltitude() - vehicle.getY();
        float climbInput = altitudeInput(vehicle, altitudeError);
        if (vehicle instanceof AirplaneEntity) {
            float pitchInput = -climbInput;
            setCruiseInputs(vehicle, turn, 0.0f, pitchInput);
        } else {
            setCruiseInputs(vehicle, turn, climbInput, forward);
            if (turn != 0.0f) {
                vehicle.setYRot(vehicle.getYRot() - turn * 1.5f);
            }
        }
        if (vehicle instanceof EngineVehicle engineVehicle && engineVehicle.getEngineTarget() < 1.0f) {
            engineVehicle.setEngineTarget(1.0f);
        }
    }

    private static boolean isAwayFromFinal(VehicleEntity vehicle, CruiseRoute route) {
        CruiseRoute.Waypoint finalTarget = route.getFinalTarget();
        if (finalTarget == null) {
            return false;
        }
        double dx = finalTarget.x() + 0.5 - vehicle.getX();
        double dz = finalTarget.z() + 0.5 - vehicle.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (route.getEffectiveLandingMode() != CruiseRoute.LandingMode.HOLDING_PATTERN) {
            return true;
        }
        return distance > FINAL_ACCELERATION_CUTOFF;
    }

    private static boolean advanceReachedWaypoints(VehicleEntity vehicle, CruiseRoute route) {
        boolean changed = false;
        int guard = 0;
        while (route.hasTarget() && guard++ < CruiseRoute.MAX_WAYPOINTS) {
            CruiseRoute.Waypoint waypoint = route.getTarget();
            if (horizontalDistance(vehicle, waypoint) > waypointReachRadius(vehicle, route)) {
                break;
            }
            if (!canAdvanceTarget(vehicle, route)) {
                break;
            }
            route.advance();
            changed = true;
        }
        return changed;
    }

    private static boolean canAdvanceTarget(VehicleEntity vehicle, CruiseRoute route) {
        if (route.isFinalTarget() && route.getEffectiveLandingMode() != CruiseRoute.LandingMode.HOLDING_PATTERN) {
            return false;
        }
        return true;
    }

    private static double waypointReachRadius(VehicleEntity vehicle, CruiseRoute route) {
        if (route.isFinalTarget() && route.getEffectiveLandingMode() == CruiseRoute.LandingMode.HOLDING_PATTERN
                && isVerticalAircraft(vehicle)) {
            return VERTICAL_AIRCRAFT_LANDING_HORIZONTAL_RADIUS;
        }
        return WAYPOINT_RADIUS;
    }

    private static int navigationAltitude(CruiseRoute route) {
        return route.getTargetAltitude();
    }

    private static boolean tickFinalLandingApproach(VehicleEntity vehicle, CruiseVehicleAccess access, CruiseRoute route, CruiseRoute.Waypoint waypoint,
                                                    double horizontalDistance, double dx, double dz, boolean clientSide) {
        if (!route.isFinalTarget()) {
            return false;
        }
        CruiseRoute.LandingMode landingMode = route.getEffectiveLandingMode();
        return landingMode != CruiseRoute.LandingMode.HOLDING_PATTERN
                && tickLandingApproach(vehicle, access, route, waypoint, horizontalDistance, dx, dz, landingMode, clientSide);
    }

    private static boolean tickLandingApproach(VehicleEntity vehicle, CruiseVehicleAccess access, CruiseRoute route, CruiseRoute.Waypoint waypoint,
                                               double horizontalDistance, double dx, double dz, CruiseRoute.LandingMode landingMode, boolean clientSide) {
        if (landingMode == CruiseRoute.LandingMode.VERTICAL) {
            if (isVerticalAircraft(vehicle)) {
                Vec3 referencePosition = horizontalReferencePosition(vehicle);
                dx = waypoint.x() + 0.5 - referencePosition.x;
                dz = waypoint.z() + 0.5 - referencePosition.z;
                horizontalDistance = Math.sqrt(dx * dx + dz * dz);
            }
            if (tickVerticalLanding(vehicle, access, route, waypoint, horizontalDistance, dx, dz, clientSide)) {
                return true;
            }
            return false;
        }
        return tickFastestLanding(vehicle, access, route, waypoint, horizontalDistance, dx, dz, clientSide);
    }

    private static boolean tickVerticalLanding(VehicleEntity vehicle, CruiseVehicleAccess access, CruiseRoute route, CruiseRoute.Waypoint waypoint,
                                               double horizontalDistance, double dx, double dz, boolean clientSide) {
        if (horizontalDistance > WAYPOINT_RADIUS && !LANDING_ACTIVE.containsKey(vehicle)) {
            return false;
        }
        LANDING_ACTIVE.put(vehicle, true);
        setBoosting(vehicle, access, false);

        double altitudeError = landingAltitudeError(vehicle, route.getFinalAltitude());
        if (isVerticalAircraft(vehicle)) {
            float yawError = yawError(vehicle.getYRot(), dx, dz);
            float turn = horizontalDistance > VERTICAL_AIRCRAFT_LANDING_HORIZONTAL_RADIUS
                    ? fastLandingTurnInput(vehicle, yawError, horizontalDistance)
                    : 0.0f;
            float climbInput = altitudeInput(vehicle, altitudeError);
            setCruiseInputs(vehicle, turn, climbInput, horizontalDistance > VERTICAL_AIRCRAFT_LANDING_HORIZONTAL_RADIUS ? 0.4f : 0.0f);
            vehicle.setDeltaMovement(brakedVelocity(vehicle, 0.90d));
            if (vehicle instanceof EngineVehicle engineVehicle && engineVehicle.getEngineTarget() < 1.0f) {
                engineVehicle.setEngineTarget(1.0f);
            }
        } else {
            tickCirclingDescent(vehicle, route, altitudeError);
        }

        double radius = isVerticalAircraft(vehicle) ? VERTICAL_AIRCRAFT_LANDING_HORIZONTAL_RADIUS : WAYPOINT_RADIUS;
        if (isLandingComplete(vehicle, waypoint, route.getFinalAltitude(), radius, LANDING_ALTITUDE_RADIUS,
                isVerticalAircraft(vehicle) ? LANDING_VERTICAL_STOP_SPEED : 0.35d)) {
            finishLanding(vehicle, access, route, clientSide);
        }
        return true;
    }

    private static boolean tickFastestLanding(VehicleEntity vehicle, CruiseVehicleAccess access, CruiseRoute route, CruiseRoute.Waypoint waypoint,
                                              double horizontalDistance, double dx, double dz, boolean clientSide) {
        LandingTarget landingTarget = fastLandingTarget(vehicle, route, waypoint);
        Vec3 referencePosition = horizontalReferencePosition(vehicle);
        dx = landingTarget.x() - referencePosition.x;
        dz = landingTarget.z() - referencePosition.z;
        horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        double landingHorizontalRadius = landingHorizontalRadius(vehicle, FAST_LANDING_HORIZONTAL_RADIUS);
        if (isVerticalAircraft(vehicle)) {
            return tickRotorcraftFastestLanding(vehicle, access, route, landingTarget, horizontalDistance, dx, dz, clientSide);
        }
        double altitudeError = landingAltitudeError(vehicle, route.getFinalAltitude());
        Integer postBrakeTicks = POST_LANDING_BRAKE.get(vehicle);
        if (postBrakeTicks != null) {
            tickPostLandingBrake(vehicle, access, route, postBrakeTicks, clientSide);
            return true;
        }

        FastLandingPlan plan = fastLandingPlan(vehicle, horizontalDistance, altitudeError, landingHorizontalRadius);
        boolean descentCommitted = LANDING_ACTIVE.containsKey(vehicle) || plan.descend();
        boolean finalBrake = FAST_LANDING_FINAL_BRAKE_ACTIVE.containsKey(vehicle) || plan.brake();
        boolean shouldLand = descentCommitted || finalBrake;
        if (!shouldLand) {
            return false;
        }
        if (descentCommitted) {
            LANDING_ACTIVE.put(vehicle, true);
        }
        if (finalBrake) {
            FAST_LANDING_FINAL_BRAKE_ACTIVE.put(vehicle, true);
        }

        float yawError = yawError(vehicle.getYRot(), dx, dz);
        float turn = horizontalDistance > FAST_LANDING_TURN_RADIUS ? fastLandingTurnInput(vehicle, yawError, horizontalDistance) : 0.0f;
        boolean activelyReducingSpeed = finalBrake;
        FlareEstimate flareEstimate = flareEstimate(vehicle, finalBrake, plan.boostTarget(), horizontalDistance,
                plan.stopTicks(), plan.rawDescent());
        boolean forceDescent = descentCommitted && shouldForceDescent(plan, flareEstimate);
        double controlAltitudeError = descentCommitted ? altitudeError : route.getTargetAltitude() - vehicle.getY();
        float climbInput = forceDescent ? 0.0f : altitudeInput(vehicle, controlAltitudeError);
        float boostTarget = activelyReducingSpeed ? 0.0f : plan.boostTarget();
        if (activelyReducingSpeed) {
            stopBoostingImmediately(vehicle, access);
        } else {
            setBoosting(vehicle, access, true, boostTarget);
        }
        if (vehicle instanceof AirplaneEntity) {
            float pitchInput = forceDescent ? FAST_DESCENT_AIRPLANE_PITCH : -climbInput;
            setCruiseInputs(vehicle, turn, finalBrake ? -1.0f : 0.0f, pitchInput);
        } else {
            float verticalInput = forceDescent ? FAST_DESCENT_VERTICAL_INPUT : climbInput;
            float forwardInput = horizontalDistance > landingHorizontalRadius ? (activelyReducingSpeed ? 0.0f : 1.0f) : 0.0f;
            setCruiseInputs(vehicle, turn, verticalInput, forwardInput);
            if (turn != 0.0f) {
                vehicle.setYRot(vehicle.getYRot() - turn * 1.5f);
            }
        }
        if (!activelyReducingSpeed && vehicle instanceof EngineVehicle engineVehicle && engineVehicle.getEngineTarget() < 1.0f) {
            engineVehicle.setEngineTarget(1.0f);
        }
        if (finalBrake) {
            brakeForLanding(vehicle, horizontalDistance);
        }

        if (isFastLandingReadyForPostBrake(vehicle, landingTarget.x(), landingTarget.z(), route.getFinalAltitude())) {
            beginPostLandingBrake(vehicle, access, route, clientSide);
        }
        return true;
    }

    private static boolean tickRotorcraftFastestLanding(VehicleEntity vehicle, CruiseVehicleAccess access, CruiseRoute route,
                                                        LandingTarget landingTarget, double horizontalDistance,
                                                        double dx, double dz, boolean clientSide) {
        double targetAltitude = rotorcraftFinalAltitude(route);
        double altitudeError = rotorcraftAltitudeError(vehicle, targetAltitude);
        RotorcraftLandingPlan plan = rotorcraftLandingPlan(vehicle, horizontalDistance, altitudeError);
        boolean descentCommitted = LANDING_ACTIVE.containsKey(vehicle) || plan.descend();
        boolean finalBrake = FAST_LANDING_FINAL_BRAKE_ACTIVE.containsKey(vehicle) || plan.brake();
        boolean shouldLand = descentCommitted || finalBrake;
        if (!shouldLand) {
            return false;
        }
        if (descentCommitted) {
            LANDING_ACTIVE.put(vehicle, true);
        }
        if (finalBrake) {
            FAST_LANDING_FINAL_BRAKE_ACTIVE.put(vehicle, true);
        }

        float yawError = yawError(vehicle.getYRot(), dx, dz);
        float turn = horizontalDistance > FAST_LANDING_TURN_RADIUS
                ? fastLandingTurnInput(vehicle, yawError, horizontalDistance)
                : 0.0f;
        boolean reversing = finalBrake && Math.abs(yawError) > ROTORCRAFT_FAST_LANDING_BACKWARD_YAW_LIMIT;
        float forwardInput = rotorcraftForwardInput(vehicle, dx, dz, horizontalDistance, finalBrake, reversing);
        float verticalInput = descentCommitted || finalBrake
                ? rotorcraftDescentInput(vehicle, altitudeError)
                : altitudeInput(vehicle, route.getTargetAltitude() - rotorcraftAltitudeY(vehicle));

        if (finalBrake) {
            stopBoostingImmediately(vehicle, access);
        } else {
            setBoosting(vehicle, access, true);
        }
        setCruiseInputs(vehicle, reversing ? 0.0f : turn, verticalInput, forwardInput);
        if (!reversing && turn != 0.0f) {
            vehicle.setYRot(vehicle.getYRot() - turn * 1.5f);
        }
        if (vehicle instanceof EngineVehicle engineVehicle && engineVehicle.getEngineTarget() < 1.0f) {
            engineVehicle.setEngineTarget(1.0f);
        }
        if (finalBrake) {
            brakeRotorcraftHorizontalVelocity(vehicle);
        }
        if (isRotorcraftFastLandingComplete(vehicle, landingTarget.x(), landingTarget.z(), targetAltitude)) {
            finishLanding(vehicle, access, route, clientSide);
        }
        return true;
    }

    private static void beginPostLandingBrake(VehicleEntity vehicle, CruiseVehicleAccess access, CruiseRoute route, boolean clientSide) {
        POST_LANDING_BRAKE.put(vehicle, POST_LANDING_BRAKE_TICKS);
        LANDING_ACTIVE.put(vehicle, true);
        FAST_LANDING_FINAL_BRAKE_ACTIVE.remove(vehicle);
        tickPostLandingBrake(vehicle, access, route, POST_LANDING_BRAKE_TICKS, clientSide);
    }

    private static void tickPostLandingBrake(VehicleEntity vehicle, CruiseVehicleAccess access, CruiseRoute route, int ticks, boolean clientSide) {
        if (ticks <= 0) {
            finishLanding(vehicle, access, route, clientSide);
            return;
        }

        stopBoostingImmediately(vehicle, access);
        if (vehicle instanceof AirplaneEntity) {
            setCruiseInputs(vehicle, 0.0f, -1.0f, 0.0f);
        } else {
            setCruiseInputs(vehicle, 0.0f, 0.0f, 0.0f);
            vehicle.setDeltaMovement(brakedVelocity(vehicle, 0.86d));
        }
        if (vehicle instanceof EngineVehicle engineVehicle) {
            engineVehicle.setEngineTarget(Math.max(0.0f, engineVehicle.getEngineTarget() - 0.1f));
        }
        POST_LANDING_BRAKE.put(vehicle, ticks - 1);
    }

    private static void tickCirclingDescent(VehicleEntity vehicle, CruiseRoute route, double altitudeError) {
        float climbInput = altitudeInput(vehicle, altitudeError);
        float turn = -1.0f;
        if (vehicle instanceof AirplaneEntity) {
            setCruiseInputs(vehicle, turn, -1.0f, Math.max(0.25f, -climbInput));
        } else {
            setCruiseInputs(vehicle, turn, climbInput, 1.0f);
            vehicle.setYRot(vehicle.getYRot() - turn * 1.5f);
        }
        if (vehicle instanceof EngineVehicle engineVehicle) {
            engineVehicle.setEngineTarget(Math.max(0.35f, engineVehicle.getEngineTarget() - 0.04f));
        }
    }

    private static void brakeForLanding(VehicleEntity vehicle, double horizontalDistance) {
        if (vehicle instanceof AirplaneEntity) {
            return;
        }
        double factor = horizontalDistance <= WAYPOINT_RADIUS ? 0.88d : 0.94d;
        if (isVerticalAircraft(vehicle)) {
            Vec3 velocity = vehicle.getDeltaMovement();
            vehicle.setDeltaMovement(velocity.x * factor, velocity.y, velocity.z * factor);
        } else {
            vehicle.setDeltaMovement(brakedVelocity(vehicle, factor));
        }
        if (vehicle instanceof EngineVehicle engineVehicle) {
            engineVehicle.setEngineTarget(Math.max(0.0f, engineVehicle.getEngineTarget() - 0.08f));
        }
    }

    private static Vec3 brakedVelocity(VehicleEntity vehicle, double factor) {
        Vec3 velocity = vehicle.getDeltaMovement();
        double yFactor = vehicle instanceof AirplaneEntity ? 0.98d : factor;
        return new Vec3(velocity.x * factor, velocity.y * yFactor, velocity.z * factor);
    }

    private static boolean isLandingComplete(VehicleEntity vehicle, CruiseRoute.Waypoint waypoint, double targetAltitude,
                                             double horizontalRadius, double altitudeRadius, double stopSpeed) {
        return isLandingComplete(vehicle, waypoint.x() + 0.5, waypoint.z() + 0.5, targetAltitude,
                horizontalRadius, altitudeRadius, stopSpeed);
    }

    private static boolean isLandingComplete(VehicleEntity vehicle, double targetX, double targetZ, double targetAltitude,
                                             double horizontalRadius, double altitudeRadius, double stopSpeed) {
        return horizontalDistance(vehicle, targetX, targetZ) <= horizontalRadius
                && Math.abs(landingContactY(vehicle) - targetAltitude) <= altitudeRadius
                && horizontalSpeed(vehicle) <= stopSpeed;
    }

    private static boolean isFastLandingReadyForPostBrake(VehicleEntity vehicle, double targetX, double targetZ, double targetAltitude) {
        double horizontalRadius = landingHorizontalRadius(vehicle, FAST_LANDING_COMPLETION_HORIZONTAL_RADIUS);
        return horizontalDistance(vehicle, targetX, targetZ) <= horizontalRadius
                && Math.abs(landingContactY(vehicle) - targetAltitude) <= FAST_LANDING_POST_BRAKE_ALTITUDE_RADIUS;
    }

    private static boolean isRotorcraftFastLandingComplete(VehicleEntity vehicle, double targetX, double targetZ, double targetAltitude) {
        Vec3 referencePosition = horizontalReferencePosition(vehicle);
        double dx = targetX - referencePosition.x;
        double dz = targetZ - referencePosition.z;
        return Math.sqrt(dx * dx + dz * dz) <= VERTICAL_AIRCRAFT_LANDING_HORIZONTAL_RADIUS
                && Math.abs(rotorcraftAltitudeY(vehicle) - targetAltitude) <= ROTORCRAFT_FAST_LANDING_COMPLETION_ALTITUDE_RADIUS
                && vehicle.getControllingPassenger() != null;
    }

    private static void finishLanding(VehicleEntity vehicle, CruiseVehicleAccess access, CruiseRoute route, boolean clientSide) {
        route.setCurrentIndex(route.getSelectedEntry().waypoints().size() - 1);
        route.setHoldingPattern(true);
        route.stopNavigation();
        setCruiseInputs(vehicle, 0.0f, 0.0f, 0.0f);
        if (isVerticalAircraft(vehicle)) {
            vehicle.setDeltaMovement(Vec3.ZERO);
        } else {
            vehicle.setDeltaMovement(vehicle.getDeltaMovement().multiply(0.2d, 0.2d, 0.2d));
        }
        stopNavigationEffects(vehicle, access);
        LANDING_ACTIVE.remove(vehicle);
        POST_LANDING_BRAKE.remove(vehicle);
        if (!clientSide) {
            CruiseModuleData.write(vehicle, route);
            syncRouteToClient(vehicle, route);
        }
    }

    private static double brakingDecay(VehicleEntity vehicle) {
        double factor = brakingFactor(vehicle);
        if (vehicle instanceof InventoryVehicleEntity inventoryVehicle) {
            double airDecay = 1.0d - inventoryVehicle.getProperties().get(VehicleStat.FRICTION);
            double horizontalDecay = inventoryVehicle.getProperties().get(VehicleStat.HORIZONTAL_DECAY);
            factor *= Mth.clamp(airDecay, 0.0d, 1.0d) * Mth.clamp(horizontalDecay, 0.0d, 1.0d);
        }
        return Mth.clamp(factor, 0.01d, 0.999d);
    }

    private static double brakingFactor(VehicleEntity vehicle) {
        if (vehicle instanceof AirplaneEntity && vehicle instanceof InventoryVehicleEntity inventoryVehicle) {
            VehicleStat brakeFactor = VehicleStat.STATS.get("brakeFactor");
            if (brakeFactor != null) {
                return Mth.clamp(inventoryVehicle.getProperties().get(brakeFactor), 0.80f, 0.995f);
            }
            return 0.95d;
        }
        return 0.94d;
    }

    private static RotorcraftLandingPlan rotorcraftLandingPlan(VehicleEntity vehicle, double horizontalDistance, double altitudeError) {
        double descent = Math.max(0.0d, -altitudeError);
        double descentTicks = rotorcraftActiveDescentTicks(vehicle, descent);
        double descentTravelTicks = Math.max(0.0d, descentTicks - ROTORCRAFT_FAST_LANDING_BRAKE_RESERVE_TICKS);
        double descentDistance = simulatedRotorcraftForwardDistance(vehicle, descentTravelTicks, 1.0f);
        double stopDistance = rotorcraftStoppingDistance(vehicle);
        boolean descend = descent > FAST_LANDING_COMPLETION_ALTITUDE_RADIUS
                && horizontalDistance <= descentDistance + ROTORCRAFT_FAST_LANDING_BRAKE_BUFFER;
        boolean brake = horizontalDistance <= stopDistance + ROTORCRAFT_FAST_LANDING_BRAKE_BUFFER;
        return new RotorcraftLandingPlan(descend, brake, descent, descentTicks, descentDistance, stopDistance);
    }

    private static double rotorcraftStoppingDistance(VehicleEntity vehicle) {
        double speed = horizontalSpeed(vehicle);
        if (speed <= LANDING_VERTICAL_STOP_SPEED) {
            return 0.0d;
        }
        double factor = ROTORCRAFT_FAST_LANDING_BRAKE_FACTOR;
        if (vehicle instanceof InventoryVehicleEntity inventoryVehicle) {
            double airDecay = 1.0d - inventoryVehicle.getProperties().get(VehicleStat.FRICTION);
            double horizontalDecay = inventoryVehicle.getProperties().get(VehicleStat.HORIZONTAL_DECAY);
            factor *= Mth.clamp(airDecay, 0.0d, 1.0d) * Mth.clamp(horizontalDecay, 0.0d, 1.0d);
        }
        factor = Mth.clamp(factor, 0.01d, 0.999d);
        return speed * factor / Math.max(0.005d, 1.0d - factor);
    }

    private static double simulatedRotorcraftForwardDistance(VehicleEntity vehicle, double ticks, float targetBoostLevel) {
        int tickLimit = Mth.clamp((int) Math.ceil(ticks), 0, ROTORCRAFT_FAST_LANDING_DISTANCE_SIMULATION_TICKS);
        if (tickLimit <= 0) {
            return 0.0d;
        }
        Vec3 velocity = vehicle.getDeltaMovement().multiply(1.0d, 0.0d, 1.0d);
        Vec3 forward = vehicle.toVec3d(vehicle.getForwardDirection());
        if (forward.lengthSqr() <= 1.0E-8d) {
            return horizontalSpeed(vehicle) * ticks;
        }
        forward = forward.normalize();
        double enginePower = vehicle instanceof EngineVehicle engineVehicle ? baseEnginePower(engineVehicle) : 1.0d;
        double engineSpeed = 0.0d;
        double horizontalDecay = 1.0d;
        if (vehicle instanceof InventoryVehicleEntity inventoryVehicle) {
            engineSpeed = inventoryVehicle.getProperties().get(VehicleStat.ENGINE_SPEED);
            double airDecay = 1.0d - inventoryVehicle.getProperties().get(VehicleStat.FRICTION);
            horizontalDecay = Mth.clamp(airDecay, 0.0d, 1.0d)
                    * Mth.clamp(inventoryVehicle.getProperties().get(VehicleStat.HORIZONTAL_DECAY), 0.0d, 1.0d);
        }
        double boostLevel = predictedRotorcraftBoostLevel(vehicle);
        double distance = 0.0d;
        for (int tick = 0; tick < tickLimit; tick++) {
            double stepRatio = Math.min(1.0d, ticks - tick);
            if (stepRatio <= 0.0d) {
                break;
            }
            velocity = velocity.scale(horizontalDecay);
            double thrust = Math.pow(enginePower, 5.0d) * engineSpeed;
            Vec3 thrustVelocity = forward.scale(thrust);
            velocity = velocity.add(thrustVelocity.scale(1.0d + boostLevel));
            double stepDistance = horizontalLength(velocity);
            distance += stepDistance * stepRatio;
            boostLevel = nextPredictedBoostLevel(boostLevel, targetBoostLevel);
        }
        return Math.max(0.0d, distance);
    }

    private static double predictedRotorcraftBoostLevel(VehicleEntity vehicle) {
        if (!(vehicle instanceof EngineVehicle engineVehicle)) {
            return 0.0d;
        }
        Float level = BOOST_LEVEL.get(engineVehicle);
        if (level != null) {
            return Mth.clamp(level, 0.0f, 1.0f);
        }
        if (engineVehicle instanceof CruiseVehicleAccess access && access.iacruise$isBoosting()) {
            return 1.0d;
        }
        return 0.0d;
    }

    private static float rotorcraftForwardInput(VehicleEntity vehicle, double dx, double dz, double horizontalDistance,
                                                boolean finalBrake, boolean reversing) {
        if (horizontalDistance <= VERTICAL_AIRCRAFT_LANDING_HORIZONTAL_RADIUS && horizontalSpeed(vehicle) <= LANDING_VERTICAL_STOP_SPEED) {
            return 0.0f;
        }
        Vec3 forward = vehicle.toVec3d(vehicle.getForwardDirection());
        double forwardError = forward.x * dx + forward.z * dz;
        if (finalBrake) {
            Vec3 velocity = vehicle.getDeltaMovement();
            double forwardSpeed = forward.x * velocity.x + forward.z * velocity.z;
            double desired = Math.abs(forwardError) > VERTICAL_AIRCRAFT_LANDING_HORIZONTAL_RADIUS
                    ? Math.signum(forwardError) * 0.4d
                    : 0.0d;
            double input = desired - forwardSpeed * 2.2d;
            return Mth.clamp((float) input, -1.0f, 1.0f);
        }
        if (reversing) {
            return Mth.clamp((float) (forwardError / 8.0d), -0.6f, 0.6f);
        }
        return horizontalDistance > VERTICAL_AIRCRAFT_LANDING_HORIZONTAL_RADIUS ? 1.0f : 0.0f;
    }

    private static float rotorcraftDescentInput(VehicleEntity vehicle, double altitudeError) {
        double verticalSpeed = vehicle.getDeltaMovement().y;
        if (Math.abs(altitudeError) <= FAST_LANDING_COMPLETION_ALTITUDE_RADIUS
                && Math.abs(verticalSpeed) <= ROTORCRAFT_FAST_LANDING_ALTITUDE_RATE_DEAD_ZONE) {
            return rotorcraftFastLandingVerticalInput(vehicle, 0.0f);
        }
        if (altitudeError < -FAST_LANDING_COMPLETION_ALTITUDE_RADIUS) {
            double remainingDescent = -altitudeError;
            double coastDescent = rotorcraftVerticalCoastDescent(vehicle);
            double descentDemand = remainingDescent - coastDescent - FAST_LANDING_COMPLETION_ALTITUDE_RADIUS;
            float targetInput = descentDemand > 0.0d
                    ? -Mth.clamp((float) (descentDemand / ROTORCRAFT_FAST_LANDING_ALTITUDE_CONTROL_RANGE), 0.0f, 1.0f)
                    : Mth.clamp((float) (-descentDemand / ROTORCRAFT_FAST_LANDING_ALTITUDE_CONTROL_RANGE), 0.0f, 1.0f);
            return rotorcraftFastLandingVerticalInput(vehicle, targetInput);
        }
        double predictedError = altitudeError - verticalSpeed * ROTORCRAFT_FAST_LANDING_ALTITUDE_LOOKAHEAD_TICKS;
        float targetInput = Mth.clamp((float) (predictedError / ROTORCRAFT_FAST_LANDING_ALTITUDE_CONTROL_RANGE), -1.0f, 1.0f);
        return rotorcraftFastLandingVerticalInput(vehicle, targetInput);
    }

    private static float rotorcraftFastLandingVerticalInput(VehicleEntity vehicle, float targetInput) {
        ALTITUDE_MEMORY.remove(vehicle);
        return Mth.clamp(targetInput, -1.0f, 1.0f);
    }

    private static void brakeRotorcraftHorizontalVelocity(VehicleEntity vehicle) {
        Vec3 velocity = vehicle.getDeltaMovement();
        vehicle.setDeltaMovement(velocity.x * ROTORCRAFT_FAST_LANDING_BRAKE_FACTOR, velocity.y, velocity.z * ROTORCRAFT_FAST_LANDING_BRAKE_FACTOR);
    }

    private static FastLandingPlan fastLandingPlan(VehicleEntity vehicle, double horizontalDistance, double altitudeError,
                                                   double horizontalRadius) {
        double rawDescent = Math.max(0.0d, -altitudeError);
        double descent = Math.max(0.0d, rawDescent - FAST_LANDING_PLAN_ALTITUDE_RADIUS);
        double currentSpeed = horizontalSpeed(vehicle);
        StopEstimate stopEstimate = stoppingEstimate(vehicle, rawDescent);
        double activeDescent = descent;
        double availableDescentDistance = Math.max(0.0d, horizontalDistance - horizontalRadius);
        float boostTarget = 1.0f;
        DescentEstimate descentEstimate = descentEstimate(vehicle, activeDescent, boostTarget);
        for (int iteration = 0; iteration < 3; iteration++) {
            boostTarget = fastLandingBoostTarget(availableDescentDistance, currentSpeed, activeDescent, descentEstimate.ticks());
            descentEstimate = descentEstimate(vehicle, activeDescent, boostTarget);
        }
        double descentRate = descentEstimate.rate();
        double descentTime = descentEstimate.ticks();
        double descentDistance = descentEstimate.distance();
        double stopDistance = stopEstimate.distance();
        double stopTicks = stopEstimate.ticks();
        boolean descend = descent > 0.0d && horizontalDistance <= descentDistance + FAST_LANDING_DESCENT_BUFFER;
        boolean brake = horizontalDistance <= stopDistance + FAST_LANDING_BRAKE_BUFFER;
        return new FastLandingPlan(descend, brake, currentSpeed, rawDescent, descent, activeDescent, descentRate, descentTime,
                descentDistance, stopDistance, stopTicks, stopEstimate.descent(), boostTarget);
    }

    private static boolean shouldForceDescent(FastLandingPlan plan, FlareEstimate flareEstimate) {
        if (plan.rawDescent() <= FAST_LANDING_COMPLETION_ALTITUDE_RADIUS) {
            return false;
        }
        return plan.rawDescent() > flareEstimate.descent() + FAST_LANDING_COMPLETION_ALTITUDE_RADIUS;
    }

    private static StopEstimate stoppingEstimate(VehicleEntity vehicle, double descent) {
        if (vehicle instanceof AirplaneEntity && vehicle instanceof EngineVehicle engineVehicle
                && vehicle instanceof InventoryVehicleEntity inventoryVehicle) {
            return simulatedAirplaneStoppingEstimate(vehicle, engineVehicle, inventoryVehicle, descent);
        }
        return geometricStoppingEstimate(vehicle);
    }

    private static FlareEstimate flareEstimate(VehicleEntity vehicle, boolean braking, float targetBoostLevel,
                                               double horizontalDistance, double stopTicks, double targetDescent) {
        double descent = Math.max(0.0d, targetDescent);
        if (descent <= FAST_LANDING_COMPLETION_ALTITUDE_RADIUS) {
            return new FlareEstimate(0.0d, 0.0d);
        }
        double horizontalWindow = Math.max(0.0d, horizontalDistance - landingHorizontalRadius(vehicle, FAST_LANDING_HORIZONTAL_RADIUS));
        double tickWindow = braking && stopTicks > 0.0d
                ? Math.min(FAST_LANDING_FLARE_SIMULATION_TICKS, Math.ceil(stopTicks))
                : FAST_LANDING_FLARE_SIMULATION_TICKS;
        if (vehicle instanceof AirplaneEntity && vehicle instanceof EngineVehicle engineVehicle
                && vehicle instanceof InventoryVehicleEntity inventoryVehicle) {
            return simulatedAirplaneFlareEstimate(vehicle, engineVehicle, inventoryVehicle, descent, braking,
                    targetBoostLevel, horizontalWindow, tickWindow);
        }
        double verticalSpeed = Math.max(0.0d, -vehicle.getDeltaMovement().y);
        double ticks = verticalSpeed <= FAST_LANDING_FLARE_VERTICAL_SPEED ? 0.0d : verticalSpeed / FAST_LANDING_FLARE_VERTICAL_SPEED;
        ticks = Math.min(ticks, tickWindow);
        double speed = horizontalSpeed(vehicle);
        if (speed > 1.0E-5d) {
            ticks = Math.min(ticks, horizontalWindow / speed);
        }
        return new FlareEstimate(Math.min(descent, verticalSpeed * ticks * 0.5d), horizontalSpeed(vehicle) * ticks);
    }

    private static FlareEstimate simulatedAirplaneFlareEstimate(VehicleEntity vehicle, EngineVehicle engineVehicle,
                                                                InventoryVehicleEntity inventoryVehicle, double targetDescent,
                                                                boolean braking, float targetBoostLevel,
                                                                double horizontalWindow, double tickWindow) {
        if (horizontalWindow <= 0.0d) {
            return new FlareEstimate(0.0d, 0.0d);
        }
        Vec3 forward = vehicle.toVec3d(vehicle.getForwardDirection());
        if (forward.lengthSqr() <= 1.0E-8d) {
            Vec3 velocity = vehicle.getDeltaMovement();
            forward = new Vec3(velocity.x, 0.0d, velocity.z);
        }
        if (forward.lengthSqr() <= 1.0E-8d) {
            return new FlareEstimate(0.0d, 0.0d);
        }
        forward = forward.normalize();

        Vec3 velocity = vehicle.getDeltaMovement();
        double enginePower = baseEnginePower(engineVehicle);
        double engineStep = enginePowerStep(engineVehicle, inventoryVehicle);
        double engineTarget = engineVehicle.getEngineTarget();
        double boostLevel = braking ? 0.0d : nextPredictedBoostLevel(predictedCurrentBoostLevel(engineVehicle), targetBoostLevel);

        double friction = inventoryVehicle.getProperties().get(VehicleStat.FRICTION);
        double lift = inventoryVehicle.getProperties().get(VehicleStat.LIFT);
        double horizontalDecay = inventoryVehicle.getProperties().get(VehicleStat.HORIZONTAL_DECAY);
        double verticalDecay = inventoryVehicle.getProperties().get(VehicleStat.VERTICAL_DECAY);
        double engineSpeed = inventoryVehicle.getProperties().get(VehicleStat.ENGINE_SPEED);
        double pitchSpeed = inventoryVehicle.getProperties().get(VehicleStat.PITCH_SPEED);
        double stabilizer = inventoryVehicle.getProperties().getAdditive(VehicleStat.STABILIZER);
        double glideFactor = inventoryVehicle.getProperties().get(VehicleStat.GLIDE_FACTOR);
        double brakeFactor = brakingFactor(vehicle);
        double inputDecay = airplaneInputDecay(friction, inventoryVehicle);

        double pitch = vehicle.getXRot();
        double pitchInput = vehicle.pressingInterpolatedZ.getSmooth();
        double contactOffset = landingContactOffset(vehicle);
        double altitudeMemory = ALTITUDE_MEMORY.getOrDefault(vehicle, 0.0f);
        double dropped = 0.0d;
        double distance = 0.0d;
        int tickLimit = Mth.clamp((int) Math.ceil(tickWindow), 1, FAST_LANDING_FLARE_SIMULATION_TICKS);

        for (int tick = 1; tick <= tickLimit; tick++) {
            double effectiveEnginePower = boostedEnginePower(engineVehicle, enginePower, boostLevel);
            velocity = simulateAirplaneVelocityStep(vehicle, velocity, forward, effectiveEnginePower, friction, lift,
                    horizontalDecay, verticalDecay, glideFactor);
            pitchInput *= inputDecay;

            if (braking) {
                engineTarget = Math.max(0.0d, engineTarget - 0.1d);
                velocity = velocity.scale(brakeFactor);
            }
            pitch += pitchSpeed * pitchInput;
            pitch *= 1.0d - stabilizer;
            forward = forwardFromRotation(vehicle.getYRot(), pitch);
            velocity = velocity.add(forward.scale(effectiveEnginePower * effectiveEnginePower * engineSpeed));

            double nextContactOffset = simulatedLandingContactOffset(vehicle, pitch);
            double contactDrop = -velocity.y + contactOffset - nextContactOffset;
            double horizontalStep = horizontalLength(velocity);
            double stepRatio = 1.0d;
            if (contactDrop > 1.0E-8d && dropped + contactDrop >= targetDescent) {
                stepRatio = Math.min(stepRatio, (targetDescent - dropped) / contactDrop);
            }
            if (horizontalStep > 1.0E-8d && distance + horizontalStep >= horizontalWindow) {
                stepRatio = Math.min(stepRatio, (horizontalWindow - distance) / horizontalStep);
            }
            stepRatio = Mth.clamp(stepRatio, 0.0d, 1.0d);
            dropped = Math.max(0.0d, dropped + contactDrop * stepRatio);
            distance += horizontalStep * stepRatio;

            if (stepRatio < 1.0d || dropped >= targetDescent || contactDrop <= FAST_LANDING_FLARE_VERTICAL_SPEED && pitchInput <= 0.0d) {
                return new FlareEstimate(Math.min(dropped, targetDescent), distance);
            }

            contactOffset = nextContactOffset;
            enginePower = enginePower + ((braking ? engineTarget : 1.0d) - enginePower) * engineStep;
            boostLevel = nextPredictedBoostLevel(boostLevel, braking ? 0.0d : targetBoostLevel);
            double targetAltitudeInput = simulatedAltitudeInput(-(targetDescent - dropped), velocity.y);
            altitudeMemory = simulatedSmoothedAltitudeInput(altitudeMemory, targetAltitudeInput);
            double targetPitchInput = -altitudeMemory;
            pitchInput = pitchInput + (targetPitchInput - pitchInput) * AIRPLANE_INPUT_INTERPOLATION_STEP;
        }

        return new FlareEstimate(Math.min(dropped, targetDescent), distance);
    }

    private static double airplaneInputDecay(double friction, InventoryVehicleEntity inventoryVehicle) {
        double decay = Mth.clamp(1.0d - friction, 0.0d, 1.0d);
        double rotationDecay = inventoryVehicle.getProperties().get(VehicleStat.ROTATION_DECAY);
        return Mth.clamp(decay * rotationDecay, 0.0d, 1.0d);
    }

    private static StopEstimate geometricStoppingEstimate(VehicleEntity vehicle) {
        double speed = horizontalSpeed(vehicle);
        if (speed <= FAST_LANDING_GROUND_STOP_SPEED) {
            return new StopEstimate(0.0d, 0.0d, 0.0d);
        }
        double factor = brakingDecay(vehicle);
        double distance = speed * factor / Math.max(0.005d, 1.0d - factor);
        distance += speed * FAST_LANDING_BRAKE_RESPONSE_TICKS;
        double ticks = Mth.clamp(Math.log(FAST_LANDING_GROUND_STOP_SPEED / speed) / Math.log(factor), 1.0d, 200.0d);
        double descent = Math.max(0.0d, -vehicle.getDeltaMovement().y) * ticks;
        return new StopEstimate(Math.max(0.0d, distance), ticks, descent);
    }

    private static StopEstimate simulatedAirplaneStoppingEstimate(VehicleEntity vehicle, EngineVehicle engineVehicle,
                                                                  InventoryVehicleEntity inventoryVehicle, double targetDescent) {
        double initialSpeed = horizontalSpeed(vehicle);
        if (initialSpeed <= FAST_LANDING_GROUND_STOP_SPEED) {
            return new StopEstimate(0.0d, 0.0d, 0.0d);
        }

        Vec3 forward = vehicle.toVec3d(vehicle.getForwardDirection());
        if (forward.lengthSqr() <= 1.0E-8d) {
            Vec3 velocity = vehicle.getDeltaMovement();
            forward = new Vec3(velocity.x, 0.0d, velocity.z);
        }
        if (forward.lengthSqr() <= 1.0E-8d) {
            return geometricStoppingEstimate(vehicle);
        }
        forward = forward.normalize();

        Vec3 velocity = vehicle.getDeltaMovement();
        double distance = 0.0d;
        double bestDistance = 0.0d;
        double bestTicks = 0.0d;
        double bestSpeed = initialSpeed;
        double dropped = 0.0d;
        double bestDropped = 0.0d;
        double remainingDescent = Math.max(0.0d, targetDescent);
        int plateauTicks = 0;

        double engineTarget = engineVehicle.getEngineTarget();
        double enginePower = baseEnginePower(engineVehicle);
        double engineStep = enginePowerStep(engineVehicle, inventoryVehicle);
        double boostLevel = 0.0d;

        double friction = inventoryVehicle.getProperties().get(VehicleStat.FRICTION);
        double lift = inventoryVehicle.getProperties().get(VehicleStat.LIFT);
        double horizontalDecay = inventoryVehicle.getProperties().get(VehicleStat.HORIZONTAL_DECAY);
        double verticalDecay = inventoryVehicle.getProperties().get(VehicleStat.VERTICAL_DECAY);
        double engineSpeed = inventoryVehicle.getProperties().get(VehicleStat.ENGINE_SPEED);
        double brakeFactor = brakingFactor(vehicle);
        double glideFactor = inventoryVehicle.getProperties().get(VehicleStat.GLIDE_FACTOR);
        double yaw = vehicle.getYRot();
        double pitch = vehicle.getXRot();
        double turnInput = vehicle.pressingInterpolatedX.getSmooth();
        double pitchInput = vehicle.pressingInterpolatedZ.getSmooth();
        double altitudeMemory = ALTITUDE_MEMORY.getOrDefault(vehicle, 0.0f);
        double contactOffset = landingContactOffset(vehicle);
        double pitchSpeed = inventoryVehicle.getProperties().get(VehicleStat.PITCH_SPEED);
        double yawSpeed = inventoryVehicle.getProperties().get(VehicleStat.YAW_SPEED);
        double stabilizer = inventoryVehicle.getProperties().getAdditive(VehicleStat.STABILIZER);
        double inputDecay = airplaneInputDecay(friction, inventoryVehicle);
        int tickLimit = FAST_LANDING_BRAKE_SIMULATION_TICKS;

        for (int tick = 1; tick <= tickLimit; tick++) {
            double effectiveEnginePower = boostedEnginePower(engineVehicle, enginePower, boostLevel);
            velocity = simulateAirplaneVelocityStep(vehicle, velocity, forward, effectiveEnginePower, friction, lift,
                    horizontalDecay, verticalDecay, glideFactor);
            turnInput *= inputDecay;
            pitchInput *= inputDecay;

            engineTarget = Math.max(0.0d, engineTarget - 0.1d);
            velocity = velocity.scale(brakeFactor);
            yaw -= yawSpeed * turnInput;
            pitch += pitchSpeed * pitchInput;
            pitch *= 1.0d - stabilizer;
            forward = forwardFromRotation(yaw, pitch);
            velocity = velocity.add(forward.scale(effectiveEnginePower * effectiveEnginePower * engineSpeed));

            double speed = horizontalLength(velocity);
            double nextContactOffset = simulatedLandingContactOffset(vehicle, pitch);
            double contactDrop = -velocity.y + contactOffset - nextContactOffset;
            if (targetDescent > 0.0d) {
                double stepRatio = 1.0d;
                if (contactDrop > 1.0E-8d && contactDrop >= remainingDescent) {
                    stepRatio = Mth.clamp(remainingDescent / contactDrop, 0.0d, 1.0d);
                }
                distance += speed * stepRatio;
                double countedDrop = contactDrop * stepRatio;
                dropped = Math.max(0.0d, dropped + countedDrop);
                remainingDescent = Math.max(0.0d, targetDescent - dropped);
                if (stepRatio < 1.0d || remainingDescent <= 0.0d) {
                    double ticks = Math.max(1.0d, tick - 1.0d + stepRatio);
                    return new StopEstimate(Math.max(0.0d, distance), ticks,
                            Math.min(Math.max(0.0d, dropped), targetDescent));
                }
            } else {
                distance += speed;
            }

            contactOffset = nextContactOffset;
            if (speed < bestSpeed - FAST_LANDING_BRAKE_PLATEAU_DELTA) {
                bestSpeed = speed;
                bestDistance = distance;
                bestTicks = tick;
                bestDropped = dropped;
                plateauTicks = 0;
            } else if (engineTarget <= 0.001d && enginePower <= 0.05d) {
                plateauTicks++;
            }
            if (plateauTicks >= FAST_LANDING_BRAKE_PLATEAU_TICKS) {
                return airBrakeEstimate(bestDistance, bestTicks, bestDropped, targetDescent);
            }

            enginePower = enginePower + (engineTarget - enginePower) * engineStep;
            boostLevel = nextPredictedBoostLevel(boostLevel, 0.0d);
            double targetAltitudeInput = simulatedAltitudeInput(-remainingDescent, velocity.y);
            altitudeMemory = simulatedSmoothedAltitudeInput(altitudeMemory, targetAltitudeInput);
            double targetPitchInput = -altitudeMemory;
            pitchInput = pitchInput + (targetPitchInput - pitchInput) * AIRPLANE_INPUT_INTERPOLATION_STEP;
        }

        return bestDistance > 0.0d ? airBrakeEstimate(bestDistance, bestTicks, bestDropped, targetDescent) : geometricStoppingEstimate(vehicle);
    }

    private static StopEstimate airBrakeEstimate(double distance, double ticks, double descent, double targetDescent) {
        return new StopEstimate(Math.max(0.0d, distance), Math.max(1.0d, ticks), Math.min(Math.max(0.0d, descent), targetDescent));
    }

    private static DescentEstimate descentEstimate(VehicleEntity vehicle, double descent, float targetBoostLevel) {
        if (descent <= 0.0d) {
            return new DescentEstimate(0.0d, 0.0d, expectedDescentRate(vehicle));
        }
        if (vehicle instanceof AirplaneEntity && vehicle instanceof EngineVehicle engineVehicle
                && vehicle instanceof InventoryVehicleEntity inventoryVehicle) {
            return simulatedAirplaneDescentEstimate(vehicle, engineVehicle, inventoryVehicle, descent, targetBoostLevel);
        }
        double descentRate = expectedDescentRate(vehicle);
        double ticks = descent / descentRate;
        double distance = horizontalSpeed(vehicle) * ticks;
        return new DescentEstimate(distance, ticks, descentRate);
    }

    private static DescentEstimate simulatedAirplaneDescentEstimate(VehicleEntity vehicle, EngineVehicle engineVehicle,
                                                                    InventoryVehicleEntity inventoryVehicle, double descent,
                                                                    float targetBoostLevel) {
        Vec3 forward = vehicle.toVec3d(vehicle.getForwardDirection());
        if (forward.lengthSqr() <= 1.0E-8d) {
            Vec3 velocity = vehicle.getDeltaMovement();
            forward = new Vec3(velocity.x, 0.0d, velocity.z);
        }
        if (forward.lengthSqr() <= 1.0E-8d) {
            double fallbackRate = expectedDescentRate(vehicle);
            double fallbackTicks = descent / fallbackRate;
            return new DescentEstimate(horizontalSpeed(vehicle) * fallbackTicks, fallbackTicks, fallbackRate);
        }
        forward = forward.normalize();

        Vec3 velocity = vehicle.getDeltaMovement();
        double engineTarget = Math.max(1.0d, engineVehicle.getEngineTarget());
        double enginePower = baseEnginePower(engineVehicle);
        double engineStep = enginePowerStep(engineVehicle, inventoryVehicle);
        double boostLevel = nextPredictedBoostLevel(predictedCurrentBoostLevel(engineVehicle), targetBoostLevel);

        double friction = inventoryVehicle.getProperties().get(VehicleStat.FRICTION);
        double lift = inventoryVehicle.getProperties().get(VehicleStat.LIFT);
        double horizontalDecay = inventoryVehicle.getProperties().get(VehicleStat.HORIZONTAL_DECAY);
        double verticalDecay = inventoryVehicle.getProperties().get(VehicleStat.VERTICAL_DECAY);
        double engineSpeed = inventoryVehicle.getProperties().get(VehicleStat.ENGINE_SPEED);
        double pitchSpeed = inventoryVehicle.getProperties().get(VehicleStat.PITCH_SPEED);
        double stabilizer = inventoryVehicle.getProperties().getAdditive(VehicleStat.STABILIZER);
        double glideFactor = inventoryVehicle.getProperties().get(VehicleStat.GLIDE_FACTOR);
        double inputDecay = airplaneInputDecay(friction, inventoryVehicle);

        double pitch = vehicle.getXRot();
        double pitchInput = vehicle.pressingInterpolatedZ.getSmooth();
        double contactOffset = landingContactOffset(vehicle);
        double dropped = 0.0d;
        double distance = 0.0d;

        for (int tick = 1; tick <= FAST_LANDING_DESCENT_SIMULATION_TICKS; tick++) {
            double effectiveEnginePower = boostedEnginePower(engineVehicle, enginePower, boostLevel);
            velocity = simulateAirplaneVelocityStep(vehicle, velocity, forward, effectiveEnginePower, friction, lift,
                    horizontalDecay, verticalDecay, glideFactor);
            pitchInput *= inputDecay;

            pitch += pitchSpeed * pitchInput;
            pitch *= 1.0d - stabilizer;
            forward = forwardFromRotation(vehicle.getYRot(), pitch);
            velocity = velocity.add(forward.scale(effectiveEnginePower * effectiveEnginePower * engineSpeed));

            double nextContactOffset = simulatedLandingContactOffset(vehicle, pitch);
            double contactDrop = -velocity.y + contactOffset - nextContactOffset;
            double horizontalStep = horizontalLength(velocity);
            double stepRatio = 1.0d;
            if (contactDrop > 1.0E-8d && dropped + contactDrop >= descent) {
                stepRatio = Mth.clamp((descent - dropped) / contactDrop, 0.0d, 1.0d);
            }
            dropped = Math.max(0.0d, dropped + contactDrop * stepRatio);
            distance += horizontalStep * stepRatio;

            if (stepRatio < 1.0d || dropped >= descent) {
                double ticks = Math.max(1.0d, tick - 1.0d + stepRatio);
                return new DescentEstimate(Math.max(0.0d, distance), ticks, Math.max(MIN_AIRPLANE_DESCENT_RATE, descent / ticks));
            }

            contactOffset = nextContactOffset;
            engineTarget = 1.0d;
            enginePower = enginePower + (engineTarget - enginePower) * engineStep;
            boostLevel = nextPredictedBoostLevel(boostLevel, targetBoostLevel);
            pitchInput = pitchInput + (FAST_DESCENT_AIRPLANE_PITCH - pitchInput) * AIRPLANE_INPUT_INTERPOLATION_STEP;
        }

        double averageRate = dropped / Math.max(1.0d, FAST_LANDING_DESCENT_SIMULATION_TICKS);
        double fallbackRate = Math.max(MIN_AIRPLANE_DESCENT_RATE, averageRate);
        double remainingTicks = Math.max(0.0d, descent - dropped) / fallbackRate;
        double averageSpeed = distance / Math.max(1.0d, FAST_LANDING_DESCENT_SIMULATION_TICKS);
        double fallbackTicks = FAST_LANDING_DESCENT_SIMULATION_TICKS + remainingTicks;
        return new DescentEstimate(distance + averageSpeed * remainingTicks, fallbackTicks, fallbackRate);
    }

    private static Vec3 simulateAirplaneVelocityStep(VehicleEntity vehicle, Vec3 velocity, Vec3 forward, double enginePower,
                                                     double friction, double lift, double horizontalDecay,
                                                     double verticalDecay, double glideFactor) {
        double descent = Math.max(0.0d, -velocity.y);
        if (glideFactor > 0.0d && descent > 0.0d) {
            velocity = velocity.add(forward.scale(descent * glideFactor * (1.0d - Math.abs(forward.y))));
        }

        double speed = velocity.length();
        if (speed > 1.0E-8d) {
            Vec3 normalized = velocity.normalize();
            double drag = Math.abs(forward.dot(normalized));
            velocity = normalized.lerp(forward, lift)
                    .scale(speed * (drag * friction + (1.0d - friction)));
        }

        double decay = Mth.clamp(1.0d - friction, 0.0d, 1.0d);
        double gravity = simulatedAirplaneGravity(vehicle, velocity, forward, enginePower);
        return new Vec3(
                velocity.x * decay * horizontalDecay,
                velocity.y * decay * verticalDecay + gravity,
                velocity.z * decay * horizontalDecay
        );
    }

    private static double simulatedAirplaneGravity(VehicleEntity vehicle, Vec3 velocity, Vec3 forward, double enginePower) {
        double horizontalDirection = 1.0d - Math.abs(forward.y);
        double gravity = Math.max(0.0d, 1.0d - velocity.length() * horizontalDirection * 1.5d) * -0.04d;
        if ("bamboo_hopper".equals(vehicle.identifier.getPath())) {
            gravity *= 1.0d - enginePower;
        }
        return gravity;
    }

    private static double baseEnginePower(EngineVehicle engineVehicle) {
        return engineVehicle.enginePower.getSmooth() * Math.sqrt(engineVehicle.getFuelUtilization());
    }

    private static double boostedEnginePower(EngineVehicle engineVehicle, double enginePower, double boostLevel) {
        if (usesControlledBoost(engineVehicle)) {
            return enginePower;
        }
        return enginePower * (1.0d + Mth.clamp((float) boostLevel, 0.0f, 1.0f));
    }

    private static double predictedCurrentBoostLevel(EngineVehicle engineVehicle) {
        if (usesControlledBoost(engineVehicle) || !(engineVehicle instanceof CruiseVehicleAccess access)) {
            return 0.0d;
        }
        return Mth.clamp(boostLevel(engineVehicle, access), 0.0f, 1.0f);
    }

    private static double nextPredictedBoostLevel(double current, double target) {
        double clampedTarget = Mth.clamp((float) target, 0.0f, 1.0f);
        double step = current < clampedTarget ? BOOST_RISE_PER_TICK : BOOST_FALL_PER_TICK;
        if (current < clampedTarget) {
            return Math.min(clampedTarget, current + step);
        }
        return Math.max(clampedTarget, current - step);
    }

    private static double enginePowerStep(EngineVehicle engineVehicle, InventoryVehicleEntity inventoryVehicle) {
        double reactionSpeed = "airship".equals(engineVehicle.identifier.getPath())
                || "cargo_airship".equals(engineVehicle.identifier.getPath())
                || "warship".equals(engineVehicle.identifier.getPath()) ? 50.0d : 20.0d;
        double acceleration = inventoryVehicle.getProperties().get(VehicleStat.ACCELERATION);
        return Mth.clamp(acceleration / reactionSpeed, 0.01d, 1.0d);
    }

    private static double horizontalLength(Vec3 velocity) {
        return Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
    }

    private static Vec3 forwardFromRotation(double yaw, double pitch) {
        double yawRadians = -yaw * Math.PI / 180.0d;
        double pitchRadians = pitch * Math.PI / 180.0d;
        double x = Math.sin(yawRadians) * Math.cos(pitchRadians);
        double y = -Math.sin(pitchRadians);
        double z = Math.cos(yawRadians) * Math.cos(pitchRadians);
        return new Vec3(x, y, z).normalize();
    }

    private static float simulatedAltitudeInput(double altitudeError, double verticalSpeed) {
        double predictedError = altitudeError - verticalSpeed * ALTITUDE_LOOKAHEAD_TICKS;
        if (Math.abs(altitudeError) <= ALTITUDE_DEAD_ZONE && Math.abs(verticalSpeed) <= ALTITUDE_RATE_DEAD_ZONE) {
            return 0.0f;
        }
        return Mth.clamp((float) (predictedError / ALTITUDE_CONTROL_RANGE), -ALTITUDE_MAX_INPUT, ALTITUDE_MAX_INPUT);
    }

    private static double expectedDescentRate(VehicleEntity vehicle) {
        double verticalRate = vehicle instanceof AirplaneEntity ? MIN_AIRPLANE_DESCENT_RATE : MIN_DESCENT_RATE;
        if (vehicle instanceof AirplaneEntity) {
            verticalRate = Math.max(verticalRate, horizontalSpeed(vehicle) * AIRPLANE_ACTIVE_DESCENT_SPEED_FACTOR);
        }
        if (vehicle instanceof Rotorcraft) {
            verticalRate = MIN_ROTORCRAFT_DESCENT_RATE;
        }
        if (vehicle instanceof InventoryVehicleEntity inventoryVehicle) {
            verticalRate = Math.max(verticalRate, inventoryVehicle.getProperties().get(VehicleStat.VERTICAL_SPEED) * ROTORCRAFT_ACTIVE_DESCENT_PROPERTY_FACTOR);
        }
        if (vehicle.getDeltaMovement().y < 0.0d) {
            verticalRate = Math.max(verticalRate, Math.abs(vehicle.getDeltaMovement().y));
        }
        return verticalRate;
    }

    private static double expectedRotorcraftDescentRate(VehicleEntity vehicle) {
        double verticalRate = MIN_ROTORCRAFT_DESCENT_RATE;
        if (vehicle instanceof InventoryVehicleEntity inventoryVehicle) {
            verticalRate = Math.max(verticalRate, inventoryVehicle.getProperties().get(VehicleStat.VERTICAL_SPEED) * ROTORCRAFT_ACTIVE_DESCENT_PROPERTY_FACTOR);
        }
        if (vehicle.getDeltaMovement().y < 0.0d) {
            verticalRate = Math.max(verticalRate, Math.abs(vehicle.getDeltaMovement().y));
        }
        return verticalRate;
    }

    private static double rotorcraftActiveDescentTicks(VehicleEntity vehicle, double descent) {
        if (descent <= FAST_LANDING_COMPLETION_ALTITUDE_RADIUS) {
            return 0.0d;
        }
        double simulatedTicks = simulatedRotorcraftDescentTicks(vehicle, descent, FAST_DESCENT_VERTICAL_INPUT);
        if (Double.isFinite(simulatedTicks)) {
            return simulatedTicks;
        }
        return descent / expectedRotorcraftDescentRate(vehicle);
    }

    private static double simulatedRotorcraftDescentTicks(VehicleEntity vehicle, double targetDescent, float targetInput) {
        double velocityY = vehicle.getDeltaMovement().y;
        double smoothInput = rotorcraftCurrentVerticalInput(vehicle);
        double verticalDecay = rotorcraftVerticalDecay(vehicle);
        double enginePower = vehicle instanceof EngineVehicle engineVehicle ? baseEnginePower(engineVehicle) : 1.0d;
        double verticalThrust = rotorcraftVerticalThrust(vehicle, enginePower);
        double gravity = rotorcraftGravity(vehicle, enginePower);
        double inputStep = rotorcraftVerticalInputStep(vehicle);
        double descent = 0.0d;
        for (int tick = 0; tick < ROTORCRAFT_FAST_LANDING_VERTICAL_SIMULATION_TICKS; tick++) {
            velocityY = velocityY * verticalDecay + gravity;
            velocityY += verticalThrust * smoothInput;
            double tickDescent = Math.max(0.0d, -velocityY);
            if (tickDescent > 0.0d && descent + tickDescent >= targetDescent) {
                return tick + (targetDescent - descent) / tickDescent;
            }
            descent += tickDescent;
            smoothInput = Mth.lerp(inputStep, smoothInput, targetInput);
        }
        return Double.POSITIVE_INFINITY;
    }

    private static double rotorcraftVerticalCoastDescent(VehicleEntity vehicle) {
        double velocityY = vehicle.getDeltaMovement().y;
        double smoothInput = rotorcraftCurrentVerticalInput(vehicle);
        double verticalDecay = rotorcraftVerticalDecay(vehicle);
        double enginePower = vehicle instanceof EngineVehicle engineVehicle ? baseEnginePower(engineVehicle) : 1.0d;
        double verticalThrust = rotorcraftVerticalThrust(vehicle, enginePower);
        double gravity = rotorcraftGravity(vehicle, enginePower);
        double inputStep = rotorcraftVerticalInputStep(vehicle);
        double descent = 0.0d;
        for (int tick = 0; tick < ROTORCRAFT_FAST_LANDING_VERTICAL_COAST_TICKS; tick++) {
            velocityY = velocityY * verticalDecay + gravity;
            velocityY += verticalThrust * smoothInput;
            descent += Math.max(0.0d, -velocityY);
            smoothInput = Mth.lerp(inputStep, smoothInput, 0.0d);
            if (velocityY >= -ROTORCRAFT_FAST_LANDING_ALTITUDE_RATE_DEAD_ZONE && Math.abs(smoothInput) <= 0.01d) {
                break;
            }
        }
        return Math.max(0.0d, descent);
    }

    private static double rotorcraftCurrentVerticalInput(VehicleEntity vehicle) {
        return Mth.clamp(vehicle.pressingInterpolatedY.getSmooth(), -1.0f, 1.0f);
    }

    private static double rotorcraftVerticalDecay(VehicleEntity vehicle) {
        if (vehicle instanceof InventoryVehicleEntity inventoryVehicle) {
            double airDecay = 1.0d - inventoryVehicle.getProperties().get(VehicleStat.FRICTION);
            double verticalDecay = inventoryVehicle.getProperties().get(VehicleStat.VERTICAL_DECAY);
            return Mth.clamp(airDecay, 0.0d, 1.0d) * Mth.clamp(verticalDecay, 0.0d, 1.0d);
        }
        return 1.0d;
    }

    private static double rotorcraftVerticalThrust(VehicleEntity vehicle, double enginePower) {
        if (vehicle instanceof InventoryVehicleEntity inventoryVehicle) {
            return Math.max(0.0d, enginePower) * inventoryVehicle.getProperties().get(VehicleStat.VERTICAL_SPEED);
        }
        return 0.0d;
    }

    private static double rotorcraftGravity(VehicleEntity vehicle, double enginePower) {
        return vehicle instanceof Rotorcraft ? (1.0d - Math.max(0.0d, enginePower)) * -0.04d : -0.04d;
    }

    private static double rotorcraftVerticalInputStep(VehicleEntity vehicle) {
        return "quadrocopter".equals(vehicle.identifier.getPath()) ? 0.2d : 0.1d;
    }

    private static double horizontalSpeed(VehicleEntity vehicle) {
        Vec3 velocity = vehicle.getDeltaMovement();
        return Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
    }

    private static boolean isVerticalAircraft(VehicleEntity vehicle) {
        return vehicle instanceof Rotorcraft;
    }

    private static double landingHorizontalRadius(VehicleEntity vehicle, double fallbackRadius) {
        return isVerticalAircraft(vehicle) ? VERTICAL_AIRCRAFT_LANDING_HORIZONTAL_RADIUS : fallbackRadius;
    }

    private static double landingAltitudeError(VehicleEntity vehicle, double targetAltitude) {
        return targetAltitude - landingContactY(vehicle);
    }

    private static double rotorcraftFinalAltitude(CruiseRoute route) {
        CruiseRoute.RouteEntry entry = route.getSelectedEntry();
        if (entry.hasLandingAltitude()) {
            return entry.landingAltitude();
        }
        return route.getFinalAltitude();
    }

    private static double rotorcraftAltitudeError(VehicleEntity vehicle, double targetAltitude) {
        return targetAltitude - rotorcraftAltitudeY(vehicle);
    }

    private static double rotorcraftAltitudeY(VehicleEntity vehicle) {
        LivingEntity pilot = vehicle.getControllingPassenger();
        return pilot == null ? vehicle.getY() : pilot.getY();
    }

    private static double landingContactY(VehicleEntity vehicle) {
        return vehicle.getY() + landingContactOffset(vehicle);
    }

    private static double landingContactOffset(VehicleEntity vehicle) {
        if (isVerticalAircraft(vehicle) && vehicle.getControllingPassenger() != null) {
            return landingContactOffsetNearReference(vehicle, vehicle.getXRot(), horizontalReferencePosition(vehicle));
        }
        return lowestLandingContactOffset(vehicle, vehicle.getXRot());
    }

    private static double simulatedLandingContactOffset(VehicleEntity vehicle, double pitch) {
        return lowestLandingContactOffset(vehicle, pitch);
    }

    private static double lowestLandingContactOffset(VehicleEntity vehicle, double pitch) {
        double offset = 0.0d;
        for (BoundingBoxDescriptor box : vehicle.getVehicleData().getBoundingBoxes()) {
            offset = Math.min(offset, transformedBoxMinYOffset(vehicle, box, pitch));
        }
        return offset;
    }

    private static double landingContactOffsetNearReference(VehicleEntity vehicle, double pitch, Vec3 referencePosition) {
        boolean hasCandidate = false;
        boolean hasContainingCandidate = false;
        double nearestDistanceSqr = Double.MAX_VALUE;
        double nearestOffset = 0.0d;
        double containingOffset = 0.0d;
        for (BoundingBoxDescriptor box : vehicle.getVehicleData().getBoundingBoxes()) {
            Vector3f center = transformedBoxCenterOffset(vehicle, box, pitch);
            double worldX = vehicle.getX() + center.x();
            double worldZ = vehicle.getZ() + center.z();
            double halfWidth = box.width() * 0.5d;
            double outsideX = Math.max(0.0d, Math.abs(referencePosition.x - worldX) - halfWidth);
            double outsideZ = Math.max(0.0d, Math.abs(referencePosition.z - worldZ) - halfWidth);
            double distanceSqr = outsideX * outsideX + outsideZ * outsideZ;
            double offset = boxBottomOffset(center, box);
            hasCandidate = true;
            if (distanceSqr <= 1.0E-8d) {
                if (!hasContainingCandidate || offset < containingOffset) {
                    containingOffset = offset;
                }
                hasContainingCandidate = true;
            }
            if (distanceSqr < nearestDistanceSqr) {
                nearestDistanceSqr = distanceSqr;
                nearestOffset = offset;
            }
        }
        if (hasContainingCandidate) {
            return Math.min(0.0d, containingOffset);
        }
        return hasCandidate ? Math.min(0.0d, nearestOffset) : 0.0d;
    }

    private static double transformedBoxMinYOffset(VehicleEntity vehicle, BoundingBoxDescriptor box, double pitch) {
        return boxBottomOffset(transformedBoxCenterOffset(vehicle, box, pitch), box);
    }

    private static double boxBottomOffset(Vector3f center, BoundingBoxDescriptor box) {
        return center.y() - box.height() * 0.5d;
    }

    private static Vector3f transformedBoxCenterOffset(VehicleEntity vehicle, BoundingBoxDescriptor box, double pitch) {
        Matrix3f transform = new Matrix3f();
        transform.rotate(com.mojang.math.Axis.YP.rotationDegrees(-quantizedAngle(vehicle.getYRot())));
        transform.rotate(com.mojang.math.Axis.XP.rotationDegrees(quantizedAngle((float) pitch)));
        transform.rotate(com.mojang.math.Axis.ZP.rotationDegrees(quantizedAngle(vehicle.getRoll())));
        return transform.transform(new Vector3f(box.x(), box.y(), box.z()));
    }

    private static float quantizedAngle(float value) {
        int floor = Mth.floor(value * 256.0f / 360.0f);
        return floor * 360.0f / 256.0f;
    }

    private static float fastLandingBoostTarget(double availableDistance, double currentSpeed, double activeDescent, double descentTime) {
        if (activeDescent <= 0.0d || descentTime <= 0.0d || currentSpeed <= 0.0d) {
            return 1.0f;
        }
        double allowedSpeed = availableDistance / descentTime;
        if (currentSpeed <= allowedSpeed * (1.0d + FAST_LANDING_SPEED_DEAD_ZONE)) {
            return 1.0f;
        }
        double ratio = Mth.clamp(allowedSpeed / currentSpeed, 0.0d, 1.0d);
        return Mth.clamp((float) ((ratio - 0.5d) / 0.5d), FAST_LANDING_MIN_CRUISE_BOOST, 1.0f);
    }

    private record FastLandingPlan(boolean descend, boolean brake, double currentSpeed, double rawDescent,
                                   double descent, double activeDescent,
                                   double descentRate, double descentTime, double descentDistance, double stopDistance,
                                   double stopTicks, double stopDescent, float boostTarget) {
    }

    private record RotorcraftLandingPlan(boolean descend, boolean brake, double descent, double descentTicks,
                                         double descentDistance, double stopDistance) {
    }

    private record StopEstimate(double distance, double ticks, double descent) {
    }

    private record FlareEstimate(double descent, double distance) {
    }

    private record DescentEstimate(double distance, double ticks, double rate) {
    }

    private record LandingTarget(double x, double z) {
    }

    private static double horizontalDistance(VehicleEntity vehicle, CruiseRoute.Waypoint waypoint) {
        return horizontalDistance(vehicle, waypoint.x() + 0.5, waypoint.z() + 0.5);
    }

    private static double horizontalDistance(VehicleEntity vehicle, double targetX, double targetZ) {
        Vec3 referencePosition = horizontalReferencePosition(vehicle);
        double dx = targetX - referencePosition.x;
        double dz = targetZ - referencePosition.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static LandingTarget fastLandingTarget(VehicleEntity vehicle, CruiseRoute route, CruiseRoute.Waypoint waypoint) {
        return new LandingTarget(waypoint.x() + 0.5, waypoint.z() + 0.5);
    }

    private static Vec3 horizontalReferencePosition(VehicleEntity vehicle) {
        LivingEntity pilot = isVerticalAircraft(vehicle) ? vehicle.getControllingPassenger() : null;
        return pilot == null ? vehicle.position() : pilot.position();
    }

    private static boolean updateInitialAltitudeProgress(VehicleEntity vehicle, CruiseRoute route) {
        if (!route.isHoldingPattern() && !route.isInitialAltitudeReached()
                && Math.abs(route.getSelectedEntry().defaultAltitude() - vehicle.getY()) <= ALTITUDE_DEAD_ZONE) {
            route.setInitialAltitudeReached(true);
            return true;
        }
        return false;
    }

    private static boolean shouldBoost(CruiseVehicleAccess access, float yawError, double altitudeError, boolean awayFromFinal) {
        if (!awayFromFinal) {
            return false;
        }
        float yaw = Math.abs(yawError);
        double altitude = Math.abs(altitudeError);
        if (access.iacruise$isBoosting()) {
            return yaw <= BOOST_EXIT_YAW && altitude <= BOOST_EXIT_ALTITUDE;
        }
        return yaw <= BOOST_ENTER_YAW && altitude <= BOOST_ENTER_ALTITUDE;
    }

    private static float altitudeInput(VehicleEntity vehicle, double altitudeError) {
        double verticalSpeed = vehicle.getDeltaMovement().y;
        float targetInput = simulatedAltitudeInput(altitudeError, verticalSpeed);
        return smoothedAltitudeInput(vehicle, targetInput);
    }

    private static float smoothedAltitudeInput(VehicleEntity vehicle, float targetInput) {
        float previousInput = ALTITUDE_MEMORY.getOrDefault(vehicle, 0.0f);
        float smoothing = Math.signum(previousInput) == Math.signum(targetInput) ? ALTITUDE_SMOOTHING : ALTITUDE_SMOOTHING * 1.35f;
        float input = Mth.lerp(smoothing, previousInput, targetInput);
        if (Math.abs(input) < 0.01f && targetInput == 0.0f) {
            ALTITUDE_MEMORY.remove(vehicle);
            return 0.0f;
        }
        ALTITUDE_MEMORY.put(vehicle, input);
        return input;
    }

    private static double simulatedSmoothedAltitudeInput(double previousInput, double targetInput) {
        double smoothing = Math.signum(previousInput) == Math.signum(targetInput) ? ALTITUDE_SMOOTHING : ALTITUDE_SMOOTHING * 1.35d;
        double input = Mth.lerp(smoothing, previousInput, targetInput);
        return Math.abs(input) < 0.01d && targetInput == 0.0d ? 0.0d : input;
    }

    private static void setCruiseInputs(VehicleEntity vehicle, float movementX, float movementY, float movementZ) {
        if (movementY < -0.01f) {
            AUTO_BRAKE_INPUT.put(vehicle, true);
        }
        vehicle.setInputs(movementX, movementY, movementZ);
    }

    private static void setBoosting(VehicleEntity vehicle, CruiseVehicleAccess access, boolean boosting) {
        setBoosting(vehicle, access, boosting, boosting ? 1.0f : 0.0f);
    }

    private static void setBoosting(VehicleEntity vehicle, CruiseVehicleAccess access, boolean boosting, float targetBoostLevel) {
        access.iacruise$setBoosting(boosting);
        if (vehicle instanceof EngineVehicle engineVehicle) {
            updateBoostLevel(engineVehicle, boosting ? targetBoostLevel : 0.0f, boosting);
        }
    }

    private static void stopBoostingImmediately(VehicleEntity vehicle, CruiseVehicleAccess access) {
        access.iacruise$setBoosting(false);
        if (vehicle instanceof EngineVehicle engineVehicle) {
            BOOST_LEVEL.remove(engineVehicle);
        }
    }

    private static void stopNavigationEffects(VehicleEntity vehicle, CruiseVehicleAccess access) {
        access.iacruise$setBoosting(false);
        if (vehicle instanceof EngineVehicle engineVehicle) {
            BOOST_LEVEL.remove(engineVehicle);
        }
        TURN_MEMORY.remove(vehicle);
        ALTITUDE_MEMORY.remove(vehicle);
        LANDING_ACTIVE.remove(vehicle);
        FAST_LANDING_FINAL_BRAKE_ACTIVE.remove(vehicle);
        AUTO_BRAKE_INPUT.remove(vehicle);
        POST_LANDING_BRAKE.remove(vehicle);
    }

    private static void updateBoostLevel(EngineVehicle vehicle, float target, boolean keepZero) {
        float current = BOOST_LEVEL.getOrDefault(vehicle, 0.0f);
        target = Mth.clamp(target, 0.0f, 1.0f);
        float step = current < target ? BOOST_RISE_PER_TICK : BOOST_FALL_PER_TICK;
        float next = current < target ? Math.min(target, current + step) : Math.max(target, current - step);
        if (next <= 0.001f) {
            if (keepZero) {
                BOOST_LEVEL.put(vehicle, 0.0f);
            } else {
                BOOST_LEVEL.remove(vehicle);
            }
        } else {
            BOOST_LEVEL.put(vehicle, next);
        }
    }

    private static float boostLevel(EngineVehicle vehicle, CruiseVehicleAccess access) {
        Float level = BOOST_LEVEL.get(vehicle);
        if (level != null) {
            return level;
        }
        return access.iacruise$isBoosting() ? 1.0f : 0.0f;
    }

    private static boolean usesControlledBoost(EngineVehicle vehicle) {
        return vehicle instanceof Rotorcraft;
    }

    public static void beforeUpdateController(VehicleEntity vehicle) {
        if (!(vehicle instanceof EngineVehicle engineVehicle) || !usesControlledBoost(engineVehicle)) {
            CONTROLLER_VELOCITY_BEFORE.remove(vehicle);
            return;
        }
        float level = BOOST_LEVEL.getOrDefault(engineVehicle, 0.0f);
        if (level <= 0.0f) {
            CONTROLLER_VELOCITY_BEFORE.remove(vehicle);
            return;
        }
        CONTROLLER_VELOCITY_BEFORE.put(vehicle, vehicle.getDeltaMovement());
    }

    public static void afterUpdateController(VehicleEntity vehicle) {
        if (!(vehicle instanceof EngineVehicle engineVehicle) || !usesControlledBoost(engineVehicle)) {
            CONTROLLER_VELOCITY_BEFORE.remove(vehicle);
            return;
        }
        Vec3 before = CONTROLLER_VELOCITY_BEFORE.remove(vehicle);
        if (before == null) {
            return;
        }
        float level = BOOST_LEVEL.getOrDefault(engineVehicle, 0.0f);
        if (level <= 0.0f) {
            return;
        }
        Vec3 after = vehicle.getDeltaMovement();
        Vec3 controllerDelta = after.subtract(before).multiply(1.0d, 0.0d, 1.0d);
        if (controllerDelta.lengthSqr() <= 1.0E-8d) {
            return;
        }
        vehicle.setDeltaMovement(after.add(controllerDelta.scale(level)));
    }

    private static float yawError(float currentYaw, double dx, double dz) {
        float targetYaw = (float) (Mth.atan2(-dx, dz) * 180.0 / Math.PI);
        return Mth.wrapDegrees(currentYaw - targetYaw);
    }

    private static float turnInput(VehicleEntity vehicle, float yawError) {
        float magnitude = Math.abs(yawError);
        if (magnitude <= YAW_DEAD_ZONE) {
            TURN_MEMORY.remove(vehicle);
            return 0.0f;
        }

        float sign;
        if (magnitude >= YAW_REVERSE_ZONE) {
            sign = TURN_MEMORY.getOrDefault(vehicle, 1.0f);
        } else {
            sign = Math.signum(yawError);
        }
        TURN_MEMORY.put(vehicle, sign);
        return sign * Mth.clamp(magnitude / 45.0f, 0.0f, 1.0f);
    }

    private static float fastLandingTurnInput(VehicleEntity vehicle, float yawError, double horizontalDistance) {
        float magnitude = Math.abs(yawError);
        double lateralError = Math.sin(Math.toRadians(magnitude)) * horizontalDistance;
        if (magnitude <= FAST_LANDING_YAW_DEAD_ZONE && lateralError <= FAST_LANDING_LATERAL_DEAD_ZONE) {
            TURN_MEMORY.remove(vehicle);
            return 0.0f;
        }

        float sign;
        if (magnitude >= YAW_REVERSE_ZONE) {
            sign = TURN_MEMORY.getOrDefault(vehicle, 1.0f);
        } else {
            sign = Math.signum(yawError);
        }
        TURN_MEMORY.put(vehicle, sign);
        return sign * Mth.clamp(magnitude / 18.0f, FAST_LANDING_MIN_TURN_INPUT, 1.0f);
    }

    private static void syncRouteToClient(VehicleEntity vehicle, CruiseRoute route) {
        if (!(vehicle.getControllingPassenger() instanceof ServerPlayer player)) {
            return;
        }
        CruiseNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new UpdateCruiseRoutePacket(vehicle.getId(), route.copy()));
    }

    private static void syncFuelInfo(VehicleEntity vehicle, EngineVehicle engineVehicle) {
        if (vehicle.tickCount % 20 != 0) {
            return;
        }
        if (!(vehicle.getControllingPassenger() instanceof ServerPlayer player)) {
            return;
        }
        int storedFuel = 0;
        ItemStack icon = ItemStack.EMPTY;
        int pendingFuel = 0;
        int pendingFuelItems = 0;
        if (engineVehicle instanceof EngineVehicleAccessor accessor) {
            for (int fuel : accessor.immersive_aircraft_cruise$getFuel()) {
                storedFuel += Math.max(0, fuel);
            }
        }
        if (engineVehicle instanceof CruiseFuelIconAccess fuelIconAccess) {
            icon = fuelIconAccess.iacruise$getBurningFuelIcon();
        }
        for (SlotDescription slot : engineVehicle.getInventoryDescription().getSlots(VehicleInventoryDescription.BOILER)) {
            ItemStack stack = engineVehicle.getInventory().getItem(slot.index());
            int fuelTime = immersive_aircraft.util.Utils.getFuelTime(stack);
            if (fuelTime > 0) {
                pendingFuel += fuelTime * stack.getCount();
                pendingFuelItems += stack.getCount();
            }
            if (icon.isEmpty() && fuelTime > 0) {
                icon = stack.copyWithCount(1);
            }
        }
        if (storedFuel <= 0 && pendingFuel <= 0) {
            icon = ItemStack.EMPTY;
        }
        float consumption = Math.max(0.0f, engineVehicle.getFuelConsumption());
        int remainingTicks = consumption <= 0.0f ? -1 : Math.round((storedFuel + pendingFuel) / consumption);
        CruiseNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new UpdateCruiseFuelPacket(vehicle.getId(), new CruiseFuelInfo(pendingFuelItems, remainingTicks, icon)));
    }

    public static Vec3 targetPosition(VehicleEntity vehicle) {
        if (!(vehicle instanceof CruiseVehicleAccess access)) {
            return null;
        }
        CruiseRoute route = access.iacruise$getRoute();
        CruiseRoute.Waypoint waypoint = route.getTarget();
        if (waypoint == null) {
            waypoint = route.getCurrentWaypoint();
        }
        if (waypoint == null) {
            waypoint = route.getFinalTarget();
        }
        double altitude = route.isHoldingPattern()
                || LANDING_ACTIVE.containsKey(vehicle)
                ? route.getFinalAltitude()
                : route.getTargetAltitude();
        return waypoint == null ? null : new Vec3(waypoint.x() + 0.5, altitude, waypoint.z() + 0.5);
    }
}
