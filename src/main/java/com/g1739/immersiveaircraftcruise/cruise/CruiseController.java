package com.g1739.immersiveaircraftcruise.cruise;

import com.g1739.immersiveaircraftcruise.ImmersiveAircraftCruise;
import com.g1739.immersiveaircraftcruise.CruiseDebug;
import com.g1739.immersiveaircraftcruise.mixin.EngineVehicleAccessor;
import com.g1739.immersiveaircraftcruise.network.CruiseNetwork;
import com.g1739.immersiveaircraftcruise.network.StopCruiseNavigationPacket;
import com.g1739.immersiveaircraftcruise.network.UpdateCruiseAccelerationPermitPacket;
import com.g1739.immersiveaircraftcruise.network.UpdateCruiseBoostPacket;
import com.g1739.immersiveaircraftcruise.network.UpdateCruiseFuelPacket;
import com.g1739.immersiveaircraftcruise.network.UpdateCruiseRoutePacket;
import com.g1739.immersiveaircraftcruise.network.SyncVehicleInventoryPacket;
import immersive_aircraft.entity.AirplaneEntity;
import immersive_aircraft.entity.EngineVehicle;
import immersive_aircraft.entity.InventoryVehicleEntity;
import immersive_aircraft.entity.Rotorcraft;
import immersive_aircraft.entity.VehicleEntity;
import immersive_aircraft.entity.inventory.VehicleInventoryDescription;
import immersive_aircraft.entity.inventory.slots.SlotDescription;
import immersive_aircraft.entity.misc.BoundingBoxDescriptor;
import immersive_aircraft.item.upgrade.VehicleStat;
import net.minecraft.advancements.Advancement;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandlerItem;
import net.minecraftforge.network.PacketDistributor;
import org.joml.Matrix3f;
import org.joml.Vector3f;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;

public final class CruiseController {
    private static final double WAYPOINT_RADIUS = 16.0;
    /** Below this heading error the first centerline is selected once. */
    private static final float L_AXIS_SELECTION_YAW_LIMIT = 15.0f;
    /** Scale the final few degrees down before the direct parallel snap. */
    private static final float L_FINE_TURN_INPUT = 0.35f;
    private static final float L_FINE_TURN_YAW_MULTIPLIER = 3.0f;
    /** Damps the aircraft's native 10-tick steering interpolation. */
    private static final float L_CONTINUOUS_TURN_RATE_DAMPING = 1.5f;
    /** Keep the aircraft within half a block on either side of the center line. */
    private static final double L_CENTERLINE_LOCK_DISTANCE = 0.5d;
    /** A short forward control horizon keeps tiny offsets from becoming 45 degree turns. */
    private static final double L_CENTERLINE_LOOKAHEAD = 16.0d;
    private static final double L_CENTERLINE_LOOKAHEAD_TICKS = 2.0d;
    /** Predict lateral drift long enough to compensate for the aircraft input smoothing. */
    private static final double L_CENTERLINE_DAMPING_HORIZON_TICKS = 10.0d;
    private static final double L_CENTERLINE_HOLD_PREDICTED_ERROR = 0.5d;
    private static final double L_CENTERLINE_HOLD_LATERAL_SPEED = 0.08d;
    private static final float L_CENTERLINE_HOLD_TURN_RATE = 0.05f;
    private static final int L_TURN_APPROACH = 0;
    private static final int L_TURN_HOLD = 2;
    private static final int L_TURN_DEBUG_SAMPLE_INTERVAL = 5;
    /** VehicleEntity constructs every input interpolator with ten native steps. */
    private static final double L_NATIVE_INPUT_INTERPOLATION_STEPS = 10.0d;
    /** Upper bound for the side-effect-free turn prediction loop. */
    private static final int L_TURN_PREDICTION_MAX_TICKS = 180;
    private static final double FINAL_ACCELERATION_CUTOFF = 100.0;
    private static final double VERTICAL_AIRCRAFT_LANDING_HORIZONTAL_RADIUS = 1.0;
    private static final double FAST_LANDING_HORIZONTAL_RADIUS = 1.0;
    private static final double FAST_LANDING_COMPLETION_HORIZONTAL_RADIUS = 1.5;
    private static final double FAST_LANDING_TURN_RADIUS = 0.25;
    private static final double LANDING_ALTITUDE_RADIUS = 1.0;
    private static final double FAST_LANDING_PLAN_ALTITUDE_RADIUS = 1.0;
    private static final double FAST_LANDING_COMPLETION_ALTITUDE_RADIUS = 1.0;
    private static final double FAST_LANDING_POST_BRAKE_STOP_SPEED = 0.06;
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
    private static final double ROTORCRAFT_TOUCHDOWN_MAX_DESCENT_SPEED = 0.08;
    private static final float ROTORCRAFT_TOUCHDOWN_DESCENT_INPUT = -0.18f;
    private static final float ROTORCRAFT_TOUCHDOWN_BRAKE_INPUT = 0.18f;
    private static final int ROTORCRAFT_FAST_LANDING_VERTICAL_SIMULATION_TICKS = 1200;
    private static final int ROTORCRAFT_FAST_LANDING_VERTICAL_COAST_TICKS = 100;
    private static final double FAST_LANDING_LATERAL_DEAD_ZONE = 0.5;
    private static final float AIRPLANE_LANDING_PITCH_LIMIT = 85.0f;
    private static final double AIRPLANE_GROUND_APPROACH_DEAD_ZONE = 0.25;
    private static final float AIRPLANE_GROUND_APPROACH_INPUT = 1.0f;
    private static final double AIRPLANE_GROUND_APPROACH_CAPTURE_RADIUS = 3.0;
    private static final float AIRPLANE_GROUND_TURNAROUND_INPUT = 0.8f;
    private static final float AIRPLANE_GROUND_TURN_INPUT = 0.8f;
    private static final float AIRPLANE_GROUND_REVERSE_YAW_DEAD_ZONE = 8.0f;
    private static final double AIRPLANE_GROUND_REVERSE_LATERAL_DEAD_ZONE = 0.75d;
    private static final float AIRPLANE_GROUND_REVERSE_TURN_MAX_INPUT = 0.45f;
    private static final int POST_LANDING_STOPPED_TICKS = 10;
    private static final float FAST_LANDING_YAW_DEAD_ZONE = 0.2f;
    private static final float FAST_LANDING_MIN_TURN_INPUT = 0.08f;
    private static final float FAST_LANDING_APPROACH_YAW_LIMIT = 45.0f;
    private static final double FAST_LANDING_ALIGN_RADIUS = 30.0d;
    private static final double FAST_LANDING_GROUND_APPROACH_RADIUS = 6.0d;
    private static final float FAST_LANDING_GROUND_TAXI_YAW_LIMIT = 70.0f;
    private static final float FAST_LANDING_GROUND_TAXI_FORWARD_INPUT = 1.0f;
    private static final float FAST_LANDING_GROUND_TAXI_THROTTLE_INPUT = 0.2f;
    private static final float FAST_LANDING_GROUND_TAXI_BRAKE_INPUT = -0.35f;
    private static final double FAST_LANDING_GROUND_TAXI_TARGET_SPEED = 8.0d / 20.0d;
    private static final double FAST_LANDING_GROUND_TAXI_SPEED_TOLERANCE = 1.0d / 20.0d;
    private static final double FAST_LANDING_GROUND_APPROACH_BRAKE_SPEED = 2.0d / 20.0d;
    private static final float AIRPLANE_GROUND_CLEAR_THROTTLE_INPUT = -1.0f;
    private static final float AIRPLANE_GROUND_ENGINE_CLEAR_THRESHOLD = 0.02f;
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
    private static final int HUD_SYNC_INTERVAL_TICKS = 5;
    private static final int PILOT_SPEED_TIMEOUT_TICKS = 20;
    private static final int BOOST_SYNC_INTERVAL_TICKS = 5;
    private static final int BOOST_SYNC_LEVEL_STEPS = 20;
    private static final float PILOT_SPEED_MAX = 512.0f;
    private static final float SPEED_ADVANCEMENT_THRESHOLD = 117.0f;
    private static final ResourceLocation SPEED_ADVANCEMENT_ID = new ResourceLocation(ImmersiveAircraftCruise.MOD_ID, "speed_117");
    private static final Map<VehicleEntity, Float> TURN_MEMORY = new WeakHashMap<>();
    private static final Map<VehicleEntity, LAlignmentPlan> L_ALIGNMENT_PLANS = new WeakHashMap<>();
    private static final Map<VehicleEntity, LTurnState> L_TURN_STATES = new WeakHashMap<>();
    private static final Map<VehicleEntity, Float> L_PENDING_HEADING_SNAPS = new WeakHashMap<>();
    private static final Map<VehicleEntity, LTurnDebugState> L_TURN_DEBUG_STATES = new WeakHashMap<>();
    private static final Map<VehicleEntity, Float> ALTITUDE_MEMORY = new WeakHashMap<>();
    private static final Map<EngineVehicle, Float> BOOST_LEVEL = new WeakHashMap<>();
    private static final Map<EngineVehicle, Float> BOOST_TARGET_LEVEL = new WeakHashMap<>();
    private static final Map<VehicleEntity, Boolean> BOOST_REQUESTED = new WeakHashMap<>();
    private static final Map<VehicleEntity, Boolean> PRELOAD_ACCELERATION_PERMITTED = new WeakHashMap<>();
    private static final Map<VehicleEntity, Boolean> LAST_SENT_ACCELERATION_PERMITTED = new WeakHashMap<>();
    private static final Map<VehicleEntity, Vec3> CONTROLLER_VELOCITY_BEFORE = new WeakHashMap<>();
    /** Last speed reported by the local pilot; expired values are not used for HUD sync. */
    private static final Map<VehicleEntity, PilotSpeedSample> PILOT_SPEEDS = new WeakHashMap<>();
    private static final Map<VehicleEntity, ItemStack> ACTIVE_MODULE = new WeakHashMap<>();
    private static final Map<VehicleEntity, String> ACTIVE_MODULE_ID = new WeakHashMap<>();
    private static final Map<VehicleEntity, ServerPlayer> LAST_PILOT = new WeakHashMap<>();
    private static final Map<VehicleEntity, ServerPlayer> LAST_DISABLED_SYNC_PILOT = new WeakHashMap<>();
    private static final Map<VehicleEntity, Boolean> LANDING_ACTIVE = new WeakHashMap<>();
    private static final Map<VehicleEntity, Boolean> FAST_LANDING_FINAL_BRAKE_ACTIVE = new WeakHashMap<>();
    private static final Map<VehicleEntity, Boolean> AUTO_BRAKE_INPUT = new WeakHashMap<>();
    private static final Map<VehicleEntity, Integer> POST_LANDING_BRAKE = new WeakHashMap<>();
    private static final Map<VehicleEntity, Vec3> FAST_LANDING_LAST_OFFSET = new WeakHashMap<>();
    private static final Map<VehicleEntity, Boolean> FAST_LANDING_PASSED_TARGET = new WeakHashMap<>();
    private static final Map<VehicleEntity, Integer> POST_LANDING_STOPPED = new WeakHashMap<>();
    private static final Map<VehicleEntity, BoostSyncState> LAST_CLIENT_BOOST_SYNC = new WeakHashMap<>();

    private record LAlignmentPlan(int stage, boolean firstAxis, float targetYaw,
                                  boolean axisOnly, double initialLateralError) {
    }

    private record LTurnState(float targetYaw, int sign, int phase) {
    }

    private record LTurnDebugState(float targetYaw, int phase, int sign, int ticksSinceSample) {
    }

    private record LTurnEstimate(double alongDistance, int ticks) {
    }

    private record PilotSpeedSample(float speed, int serverTick) {
    }

    private CruiseController() {
    }

    public static void register() {
        MinecraftForge.EVENT_BUS.addListener(CruiseController::onServerTick);
    }

    private static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END
                || event.getServer().getTickCount() % HUD_SYNC_INTERVAL_TICKS != 0) {
            return;
        }
        syncFuelInfo(event.getServer());
    }

    private static void syncFuelInfo(MinecraftServer server) {
        Set<EngineVehicle> vehicles = Collections.newSetFromMap(new IdentityHashMap<>());
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.getRootVehicle() instanceof EngineVehicle vehicle
                    && CruiseModuleData.hasModule(vehicle)) {
                vehicles.add(vehicle);
            }
        }
        for (EngineVehicle vehicle : vehicles) {
            syncFuelInfo(vehicle);
        }
    }

    public static boolean hasCruiseModule(Entity entity) {
        return entity instanceof VehicleEntity vehicle && CruiseModuleData.hasModule(vehicle);
    }

    public static boolean isPilot(VehicleEntity vehicle, ServerPlayer player) {
        return player != null && vehicle.getControllingPassenger() == player;
    }

    /**
     * The switch behind the preload screen's "auto slowdown" button: slow a cruise aircraft down while
     * the route ahead is still loading. Session state on purpose - the screen is the only place it is
     * set, so nothing can disagree with a config file.
     */
    private static boolean preloadAutoDeceleration = true;

    public static boolean preloadAutoDeceleration() {
        return preloadAutoDeceleration;
    }

    public static void setPreloadAutoDeceleration(boolean enabled) {
        preloadAutoDeceleration = enabled;
    }

    /** Returns whether a player is still seated on the aircraft's root vehicle. */
    public static boolean isOnboard(VehicleEntity vehicle, ServerPlayer player) {
        return vehicle != null && player != null && player.getRootVehicle() == vehicle;
    }

    /**
     * Keeps the complete cruise group paired: the aircraft itself and every
     * onboard player must remain visible to every other onboard player.  The
     * predicate is intentionally module-based (rather than route-enabled) so a
     * dismounted/disabled transition is handed back to vanilla tracking cleanly.
     */
    public static boolean shouldKeepCruiseEntityTracked(Entity tracked, ServerPlayer observer) {
        if (observer == null || tracked == observer) {
            return false;
        }
        VehicleEntity vehicle;
        if (tracked instanceof VehicleEntity candidate) {
            vehicle = candidate;
            return hasCruiseModule(vehicle) && isOnboard(vehicle, observer);
        }
        if (!(tracked instanceof ServerPlayer trackedPlayer)
                || !(trackedPlayer.getRootVehicle() instanceof VehicleEntity candidate)) {
            return false;
        }
        vehicle = candidate;
        return hasCruiseModule(vehicle)
                && isOnboard(vehicle, trackedPlayer)
                && isOnboard(vehicle, observer);
    }

    /**
     * One authoritative movement transaction for all server-side occupants.
     * This is called after both native vehicle ticks and client move packets so
     * neither path can leave a passenger at a stale position/chunk center.
     */
    public static void synchronizeCruiseMovement(VehicleEntity vehicle, String trigger) {
        if (vehicle == null || vehicle.level().isClientSide() || !hasCruiseModule(vehicle)) {
            return;
        }
        CruiseChunkSendScheduler.updateEntityChunkTicket(vehicle);
        int corrected = 0;
        for (Entity passenger : vehicle.getPassengers()) {
            corrected += synchronizePassengerTree(vehicle, passenger, trigger);
        }
        if (corrected > 0) {
            CruiseDebug.debug(ImmersiveAircraftCruise.LOGGER,
                    "[CruiseChunks] movement transaction: vehicleId={}, trigger={}, passengers={}, tick={}",
                    vehicle.getId(), trigger, corrected, vehicle.tickCount);
        }
    }

    private static int synchronizePassengerTree(VehicleEntity vehicle, Entity passenger, String trigger) {
        int corrected = 0;
        if (passenger instanceof ServerPlayer player && isOnboard(vehicle, player)) {
            double beforeDistance = Math.sqrt(player.distanceToSqr(vehicle));
            double beforeX = player.getX();
            double beforeY = player.getY();
            double beforeZ = player.getZ();
            vehicle.positionRider(player);
            player.serverLevel().getChunkSource().move(player);
            corrected++;
            if (beforeDistance > 1.0d || beforeX != player.getX()
                    || beforeY != player.getY() || beforeZ != player.getZ()) {
                CruiseDebug.info(ImmersiveAircraftCruise.LOGGER,
                        "[CruiseChunks] passenger transaction correction: player={}, vehicleId={}, "
                                + "trigger={}, distanceBefore={}, posAfter={}, playerChunk={}, vehicleChunk={}",
                        player.getScoreboardName(), vehicle.getId(), trigger, beforeDistance,
                        String.format("%.2f/%.2f/%.2f", player.getX(), player.getY(), player.getZ()),
                        player.chunkPosition(), vehicle.chunkPosition());
            }
        }
        for (Entity nested : passenger.getPassengers()) {
            corrected += synchronizePassengerTree(vehicle, nested, trigger);
        }
        return corrected;
    }

    private static boolean isLocalClientPilot(VehicleEntity vehicle) {
        return vehicle.level().isClientSide()
                && vehicle.getControllingPassenger() instanceof Player player
                && player.isLocalPlayer();
    }

    private static boolean hasEngineFuel(EngineVehicle engineVehicle) {
        return engineVehicle.getFuelUtilization() > 0.0f;
    }

    private static boolean canApplyCruiseModifiers(EngineVehicle vehicle, CruiseVehicleAccess access) {
        CruiseRoute route = access.iacruise$getRoute();
        return route != null && route.isEnabled()
                && (vehicle.level().isClientSide() || CruiseModuleData.hasModule(vehicle));
    }

    public static void serverEngineTick(EngineVehicle engineVehicle) {
        VehicleEntity vehicle = engineVehicle;
        if (vehicle.level().isClientSide()) {
            return;
        }
        if (!CruiseModuleData.hasModule(vehicle)) {
            return;
        }
        if (!hasEngineFuel(engineVehicle) && engineVehicle instanceof CruiseVehicleAccess access) {
            CruiseRoute route = serverRoute(vehicle, access);
            if (route.isEnabled()) {
                pauseNavigationForNoFuel(vehicle, access, route, false);
            }
        }
    }

    public static void tick(VehicleEntity vehicle, boolean braking) {
        if (!(vehicle instanceof CruiseVehicleAccess access)) {
            return;
        }
        boolean clientSide = vehicle.level().isClientSide();
        if (clientSide && !isLocalClientPilot(vehicle)) {
            // Only the local pilot predicts cruise motion. Other clients render
            // the server vehicle and keep their own route/cache viewer state.
            return;
        }
        boolean automaticBrakeInput = Boolean.TRUE.equals(AUTO_BRAKE_INPUT.remove(vehicle));
        CruiseRoute clientRoute = clientSide ? access.iacruise$getRoute() : null;
        boolean hasAuthoritativeClientRoute = clientRoute != null && clientRoute.isEnabled();

        if (!CruiseModuleData.hasModule(vehicle) && !hasAuthoritativeClientRoute) {
            ACTIVE_MODULE.remove(vehicle);
            CruiseRoute route = access.iacruise$getRoute();
            if (!clientSide) {
                route.stopNavigation();
                access.iacruise$setRoute(route);
                syncDisabledRouteToPilot(vehicle, route);
            } else {
                route.stopNavigation();
            }
            LAST_PILOT.remove(vehicle);
            access.iacruise$setBoosting(false);
            BOOST_REQUESTED.remove(vehicle);
            PRELOAD_ACCELERATION_PERMITTED.remove(vehicle);
            LAST_SENT_ACCELERATION_PERMITTED.remove(vehicle);
            if (vehicle instanceof EngineVehicle engineVehicle) {
                BOOST_LEVEL.remove(engineVehicle);
                BOOST_TARGET_LEVEL.remove(engineVehicle);
            }
            TURN_MEMORY.remove(vehicle);
            L_ALIGNMENT_PLANS.remove(vehicle);
            L_TURN_STATES.remove(vehicle);
            L_PENDING_HEADING_SNAPS.remove(vehicle);
            ALTITUDE_MEMORY.remove(vehicle);
            LANDING_ACTIVE.remove(vehicle);
            FAST_LANDING_FINAL_BRAKE_ACTIVE.remove(vehicle);
            AUTO_BRAKE_INPUT.remove(vehicle);
            POST_LANDING_BRAKE.remove(vehicle);
            clearLandingAssistState(vehicle);
            LAST_CLIENT_BOOST_SYNC.remove(vehicle);
            return;
        }

        CruiseRoute route = clientSide ? clientRoute : serverRoute(vehicle, access);
        if (!clientSide && vehicle.tickCount % 20 == 0) {
            syncRouteToClient(vehicle, route);
        }

        if (!route.isEnabled()) {
            if (!clientSide) {
                syncDisabledRouteToPilot(vehicle, route);
            }
            L_PENDING_HEADING_SNAPS.remove(vehicle);
            stopNavigationEffects(vehicle, access);
            return;
        }
        if (!clientSide) {
            LAST_DISABLED_SYNC_PILOT.remove(vehicle);
        }

        ServerPlayer pilot = controllingServerPlayer(vehicle);
        if (!clientSide) {
            ServerPlayer lastPilot = LAST_PILOT.get(vehicle);
            if (pilot != null) {
                if (lastPilot != null && lastPilot != pilot) {
                    stopNavigation(vehicle, access, route, LAST_PILOT.remove(vehicle), false);
                    return;
                }
                LAST_PILOT.put(vehicle, pilot);
            } else {
                stopNavigation(vehicle, access, route, LAST_PILOT.remove(vehicle), false);
                return;
            }
        }
        if (handleNavigationHorizontalCollision(vehicle, access, route, clientSide)) {
            return;
        }

        if (braking && !automaticBrakeInput) {
            stopNavigation(vehicle, access, route, pilot, clientSide);
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
        boolean useLShapedRoute = route.shouldUseLShaped(referencePosition.x, referencePosition.z);
        if (useLShapedRoute
                && tickLShapedNavigation(vehicle, access, route, waypoint, clientSide)) {
            return;
        }
        referencePosition = horizontalReferencePosition(vehicle);
        CruiseRoute.Waypoint steeringWaypoint = route.getNavigationTarget(referencePosition.x, referencePosition.z);
        double actualDx = waypoint.x() + 0.5 - referencePosition.x;
        double actualDz = waypoint.z() + 0.5 - referencePosition.z;
        double actualDistance = Math.sqrt(actualDx * actualDx + actualDz * actualDz);
        double dx = steeringWaypoint.x() + 0.5 - referencePosition.x;
        double dz = steeringWaypoint.z() + 0.5 - referencePosition.z;
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        boolean lLandingHandoff = !route.shouldUseLShaped(referencePosition.x, referencePosition.z)
                || route.getLoadingStage() >= CruiseRoute.L_STAGE_FINAL_LEG
                && actualDistance <= CruiseRoute.L_FINAL_LANDING_HANDOFF_DISTANCE;
        if (lLandingHandoff && tickFinalLandingApproach(vehicle, access, route, waypoint, actualDistance, actualDx, actualDz, clientSide)) {
            return;
        }
        if (lLandingHandoff && actualDistance <= waypointReachRadius(vehicle, route) && canAdvanceTarget(vehicle, route)) {
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
                clearLandingAssistState(vehicle);
                tickHoldingPattern(vehicle, route);
                return;
            }
            referencePosition = horizontalReferencePosition(vehicle);
            steeringWaypoint = route.getNavigationTarget(referencePosition.x, referencePosition.z);
            actualDx = waypoint.x() + 0.5 - referencePosition.x;
            actualDz = waypoint.z() + 0.5 - referencePosition.z;
            actualDistance = Math.sqrt(actualDx * actualDx + actualDz * actualDz);
            dx = steeringWaypoint.x() + 0.5 - referencePosition.x;
            dz = steeringWaypoint.z() + 0.5 - referencePosition.z;
            horizontalDistance = Math.sqrt(dx * dx + dz * dz);
            if (!route.shouldUseLShaped(referencePosition.x, referencePosition.z)
                    || route.getLoadingStage() >= CruiseRoute.L_STAGE_FINAL_LEG
                    && actualDistance <= CruiseRoute.L_FINAL_LANDING_HANDOFF_DISTANCE
                    && tickFinalLandingApproach(vehicle, access, route, waypoint, actualDistance, actualDx, actualDz, clientSide)) {
                return;
            }
        }
        if (tickRotorcraftHoldingApproach(vehicle, access, route, waypoint, horizontalDistance)) {
            return;
        }

        // A long L-mode leg with a sub-chunk short axis has no corner to fly,
        // but it still needs the endpoint chunk's long-axis center line. Keep
        // using the L center-line controller instead of the ordinary 3-degree
        // waypoint dead zone, which otherwise lets wind drift grow while the
        // aircraft is already moving along the long axis.
        if (route.isLongLRouteCandidate()
                && !route.shouldUseLShaped(referencePosition.x, referencePosition.z)) {
            tickDirectLLineNavigation(vehicle, access, route);
            return;
        }

        int targetAltitude = navigationAltitude(route);
        double altitudeError = targetAltitude - vehicle.getY();
        float yawError = yawError(vehicle.getYRot(), dx, dz);
        float turn = turnInput(vehicle, yawError);
        float climbInput = altitudeInput(vehicle, altitudeError);
        if (tickRotorcraftLandingAlignGuard(vehicle, access, route, horizontalDistance, yawError, climbInput)) {
            return;
        }

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
        setBoosting(vehicle, access, shouldBoost(vehicle, yawError, altitudeError, awayFromFinal));
    }

    /**
     * L mode deliberately avoids the ordinary waypoint steering loop. It
     * first captures a chunk-center line, locks the short axis, starts the
     * corner turn at the predicted braking/rotation distance, and then holds
     * the long axis until the final landing handoff radius.
     */
    private static boolean tickLShapedNavigation(VehicleEntity vehicle, CruiseVehicleAccess access,
                                                  CruiseRoute route, CruiseRoute.Waypoint waypoint,
                                                  boolean clientSide) {
        Vec3 reference = horizontalReferencePosition(vehicle);
        int stage = route.getLoadingStage();

        if ((stage == CruiseRoute.L_STAGE_AXIS_ALIGN
                || stage == CruiseRoute.L_STAGE_CENTERLINE_CAPTURE)
                && shouldBeginLTurn(vehicle, route, reference)) {
            logLTurnPlan(vehicle, route, waypoint, reference, stage);
            setLStage(vehicle, route, CruiseRoute.L_STAGE_CORNER_TURN, clientSide);
            stage = CruiseRoute.L_STAGE_CORNER_TURN;
        }

        if (stage == CruiseRoute.L_STAGE_AXIS_ALIGN) {
            float axisError = lAxisYawError(vehicle, route, true);
            if (Math.abs(axisError) > L_AXIS_SELECTION_YAW_LIMIT) {
                tickLContinuousAxisAlignment(vehicle, access, route);
                return true;
            }
            selectLFirstLine(vehicle, access, route, axisError, clientSide);
            setLStage(vehicle, route, CruiseRoute.L_STAGE_CENTERLINE_CAPTURE, clientSide);
            stage = CruiseRoute.L_STAGE_CENTERLINE_CAPTURE;
        }

        if (stage == CruiseRoute.L_STAGE_CENTERLINE_CAPTURE) {
            CruiseRoute.Waypoint captureTarget = route.getLNavigationTarget(reference.x, reference.z);
            float axisError = lAxisYawError(vehicle, route, true);
            double lateralError = lLateralError(route, captureTarget, reference, true);
            if (Math.abs(lateralError) <= L_CENTERLINE_LOCK_DISTANCE
                    && lHeadingSettled(vehicle, axisError)) {
                TURN_MEMORY.remove(vehicle);
                setLStage(vehicle, route, CruiseRoute.L_STAGE_FIRST_LEG, clientSide);
                stage = CruiseRoute.L_STAGE_FIRST_LEG;
            } else {
                tickLFirstLegCapture(vehicle, access, route, captureTarget, reference);
                return true;
            }
        }

        if (stage == CruiseRoute.L_STAGE_FIRST_LEG) {
            reference = horizontalReferencePosition(vehicle);
            CruiseRoute.Waypoint corner = route.getLCornerTarget();
            float axisError = lAxisYawError(vehicle, route, true);
            double lateralError = lLateralError(route, corner, reference, true);
            if (Math.abs(lateralError) <= L_CENTERLINE_LOCK_DISTANCE
                    && lHeadingSettled(vehicle, axisError)) {
                reference = horizontalReferencePosition(vehicle);
            } else if (Math.abs(lateralError) > L_CENTERLINE_LOCK_DISTANCE) {
                tickLCenterlineCorrection(vehicle, access, route, true);
                return true;
            }
            axisError = lAxisYawError(vehicle, route, true);
            if (!lHeadingSettled(vehicle, axisError)) {
                setLStage(vehicle, route, CruiseRoute.L_STAGE_CENTERLINE_CAPTURE, clientSide);
                tickLFirstLegCapture(vehicle, access, route, corner,
                        horizontalReferencePosition(vehicle));
                return true;
            }
            double along = lAlongDistance(route, corner, reference, true);
            LTurnEstimate turnEstimate = predictLTurn(vehicle, route);
            if (along <= turnEstimate.alongDistance()
                    || along <= CruiseRoute.L_SHAPED_MIN_SHORT_AXIS_DISTANCE) {
                logLTurnPlan(vehicle, route, waypoint, reference, stage, along, turnEstimate);
                setLStage(vehicle, route, CruiseRoute.L_STAGE_CORNER_TURN, clientSide);
                stage = CruiseRoute.L_STAGE_CORNER_TURN;
            } else {
                tickLLockedLeg(vehicle, access, route, true);
                return true;
            }
        }

        if (stage == CruiseRoute.L_STAGE_CORNER_TURN) {
            float axisError = lAxisYawError(vehicle, route, false);
            float turnInput = lAxisTurnInput(vehicle, route, false);
            if (!lHeadingSettled(vehicle, axisError)) {
                setLTurningInputs(vehicle, access, turnInput);
                return true;
            }
            setLTurningInputs(vehicle, access, 0.0f);
            TURN_MEMORY.remove(vehicle);
            setLStage(vehicle, route, CruiseRoute.L_STAGE_FINAL_LEG, clientSide);
            stage = CruiseRoute.L_STAGE_FINAL_LEG;
        }

        if (stage == CruiseRoute.L_STAGE_FINAL_LEG) {
            reference = horizontalReferencePosition(vehicle);
            double actualDx = waypoint.x() + 0.5d - reference.x;
            double actualDz = waypoint.z() + 0.5d - reference.z;
            double actualDistance = Math.sqrt(actualDx * actualDx + actualDz * actualDz);
            if (route.isFinalTarget() && actualDistance <= CruiseRoute.L_FINAL_LANDING_HANDOFF_DISTANCE) {
                return false;
            }
            if (!route.isFinalTarget() && actualDistance <= waypointReachRadius(vehicle, route)
                    && canAdvanceTarget(vehicle, route)) {
                route.advance();
                if (!clientSide) {
                    CruiseModuleData.write(vehicle, route);
                    syncRouteToClient(vehicle, route);
                }
                return true;
            }
            float axisError = lAxisYawError(vehicle, route, false);
            CruiseRoute.Waypoint lineTarget = route.getLNavigationTarget(reference.x, reference.z);
            double lateralError = lLateralError(route, lineTarget, reference, false);
            if (Math.abs(lateralError) <= L_CENTERLINE_LOCK_DISTANCE
                    && lHeadingSettled(vehicle, axisError)) {
                reference = horizontalReferencePosition(vehicle);
                axisError = lAxisYawError(vehicle, route, false);
            } else if (Math.abs(lateralError) > L_CENTERLINE_LOCK_DISTANCE) {
                tickLCenterlineCorrection(vehicle, access, route, false);
                return true;
            }
            if (!lHeadingSettled(vehicle, axisError)) {
                setLStage(vehicle, route, CruiseRoute.L_STAGE_CORNER_TURN, clientSide);
                return true;
            }
            tickLLockedLeg(vehicle, access, route, false);
            return true;
        }
        return true;
    }

    private static void tickLFirstLegCapture(VehicleEntity vehicle, CruiseVehicleAccess access,
                                             CruiseRoute route, CruiseRoute.Waypoint target, Vec3 reference) {
        float turn = lCenterlineTurnInput(vehicle, route, target, reference, true);
        float climbInput = altitudeInput(vehicle, navigationAltitude(route) - vehicle.getY());
        setBoosting(vehicle, access, false);
        if (vehicle instanceof AirplaneEntity) {
            setCruiseInputs(vehicle, turn, 0.0f, -climbInput);
        } else {
            setCruiseInputs(vehicle, turn, climbInput, 1.0f);
        }
        if (vehicle instanceof EngineVehicle engineVehicle) {
            engineVehicle.setEngineTarget(1.0f);
        }
    }

    /** Keeps propulsion on while the first axis is being brought below the 45 degree gate. */
    private static void tickLContinuousAxisAlignment(VehicleEntity vehicle, CruiseVehicleAccess access,
                                                      CruiseRoute route) {
        float turn = lAxisTurnInput(vehicle, route, true);
        float climbInput = altitudeInput(vehicle, navigationAltitude(route) - vehicle.getY());
        boolean brake = !lHeadingSettled(vehicle, lAxisYawError(vehicle, route, true));
        setBoosting(vehicle, access, false);
        if (vehicle instanceof AirplaneEntity) {
            setCruiseInputs(vehicle, turn, brake ? -1.0f : 0.0f, -climbInput);
        } else {
            setCruiseInputs(vehicle, turn, climbInput, brake ? 0.0f : 1.0f);
        }
        if (vehicle instanceof AirplaneEntity && vehicle instanceof EngineVehicle engineVehicle) {
            engineVehicle.setEngineTarget(brake ? 0.0f : 1.0f);
        }
    }

    private static void selectLFirstLine(VehicleEntity vehicle, CruiseVehicleAccess access,
                                         CruiseRoute route, float axisError, boolean clientSide) {
        if (route.getLFirstLineCoordinate() != null) {
            return;
        }
        // At the low-angle gate the current chunk center is the test target;
        // do not push selection into a farther line based on current speed.
        route.setLFirstLineCoordinate(route.selectLFirstLine(vehicle.getX(), vehicle.getZ(), 0.0d));
        if (!clientSide) {
            CruiseModuleData.write(vehicle, route);
            syncRouteToClient(vehicle, route);
        }
    }

    private static double lYawSpeed(VehicleEntity vehicle) {
        if (vehicle instanceof InventoryVehicleEntity inventoryVehicle) {
            return Math.max(0.01d, inventoryVehicle.getProperties().get(VehicleStat.YAW_SPEED));
        }
        return 5.0d;
    }

    private static boolean shouldBeginLTurn(VehicleEntity vehicle, CruiseRoute route, Vec3 reference) {
        CruiseRoute.Waypoint corner = route.getLCornerTarget();
        if (corner == null) {
            return false;
        }
        double remaining = lAlongDistance(route, corner, reference, true);
        LTurnEstimate estimate = predictLTurn(vehicle, route);
        return remaining <= estimate.alongDistance()
                || remaining <= CruiseRoute.L_SHAPED_MIN_SHORT_AXIS_DISTANCE;
    }

    private static void logLTurnPlan(VehicleEntity vehicle, CruiseRoute route,
                                     CruiseRoute.Waypoint waypoint, Vec3 reference, int stage) {
        CruiseRoute.Waypoint corner = route.getLCornerTarget();
        if (corner == null) {
            return;
        }
        LTurnEstimate estimate = predictLTurn(vehicle, route);
        logLTurnPlan(vehicle, route, waypoint, reference, stage,
                lAlongDistance(route, corner, reference, true), estimate);
    }

    private static void logLTurnPlan(VehicleEntity vehicle, CruiseRoute route,
                                     CruiseRoute.Waypoint waypoint, Vec3 reference, int stage,
                                     double remaining, LTurnEstimate estimate) {
        double boost = vehicle instanceof EngineVehicle engineVehicle
                && vehicle instanceof CruiseVehicleAccess access
                ? boostLevel(engineVehicle, access) : 0.0d;
        CruiseDebug.debug(ImmersiveAircraftCruise.LOGGER,
                "[CruiseLTurnPlan] vehicle={} stage={} pos={}/{} waypoint={}/{} remaining={} predictedAlong={} ticks={} predictedGate={} min16Gate={} speed={} boost={} yaw={} targetYaw={} firstAxisX={} firstSign={}",
                vehicle.getId(), stage, reference.x, reference.z, waypoint.x(), waypoint.z(), remaining,
                estimate.alongDistance(), estimate.ticks(), remaining <= estimate.alongDistance(),
                remaining <= CruiseRoute.L_SHAPED_MIN_SHORT_AXIS_DISTANCE,
                horizontalLength(vehicle.getDeltaMovement()), boost,
                vehicle.getYRot(), lAxisYaw(route, false), route.isLFirstAxisX(), route.lFirstAxisSign());
    }

    /**
     * Predicts how far the vehicle will continue along the first axis while
     * turning onto the second axis. The prediction follows the native yaw
     * equation (including the ten-step input interpolation) and the same
     * engine-off/brake decay used by the airplane controller. It never mutates
     * the entity; the result is used only to choose the turn entry tick.
     */
    private static LTurnEstimate predictLTurn(VehicleEntity vehicle, CruiseRoute route) {
        double targetYaw = lAxisYaw(route, false);
        double yaw = vehicle.getYRot();
        double yawSpeed = Math.max(0.01d, lYawSpeed(vehicle));
        double turnInput = vehicle.pressingInterpolatedX.getSmooth();
        int turnSign = lTurnSign(Mth.wrapDegrees((float) (yaw - targetYaw)));
        Vec3 velocity = vehicle.getDeltaMovement();
        boolean firstAxisX = route.isLFirstAxisX();
        int firstAxisSign = route.lFirstAxisSign();
        double alongDistance = 0.0d;
        int ticks = 0;

        EngineVehicle engineVehicle = vehicle instanceof EngineVehicle engine ? engine : null;
        InventoryVehicleEntity inventoryVehicle = vehicle instanceof InventoryVehicleEntity inventory
                ? inventory : null;
        boolean simulateAirplane = vehicle instanceof AirplaneEntity
                && engineVehicle != null && inventoryVehicle != null;
        double enginePower = simulateAirplane ? baseEnginePower(engineVehicle) : 0.0d;
        double engineStep = simulateAirplane ? enginePowerStep(engineVehicle, inventoryVehicle) : 0.0d;
        double boostLevel = simulateAirplane ? predictedCurrentBoostLevel(engineVehicle) : 0.0d;
        double friction = simulateAirplane ? inventoryVehicle.getProperties().get(VehicleStat.FRICTION) : 0.0d;
        double lift = simulateAirplane ? inventoryVehicle.getProperties().get(VehicleStat.LIFT) : 0.0d;
        double horizontalDecay = simulateAirplane
                ? inventoryVehicle.getProperties().get(VehicleStat.HORIZONTAL_DECAY) : 1.0d;
        double verticalDecay = simulateAirplane
                ? inventoryVehicle.getProperties().get(VehicleStat.VERTICAL_DECAY) : 1.0d;
        double glideFactor = simulateAirplane
                ? inventoryVehicle.getProperties().get(VehicleStat.GLIDE_FACTOR) : 0.0d;
        double engineSpeed = simulateAirplane
                ? inventoryVehicle.getProperties().get(VehicleStat.ENGINE_SPEED) : 0.0d;
        double pitch = vehicle.getXRot();
        double stabilizer = simulateAirplane
                ? inventoryVehicle.getProperties().getAdditive(VehicleStat.STABILIZER) : 0.0d;
        double brakeFactor = simulateAirplane ? brakingFactor(vehicle) : 1.0d;

        for (int tick = 1; tick <= L_TURN_PREDICTION_MAX_TICKS; tick++) {
            float error = Mth.wrapDegrees((float) (yaw - targetYaw));
            boolean turnComplete = Math.abs(error) <= yawSpeed
                    || turnSign == 0
                    || lTurnSign(error) != turnSign;
            double desiredInput = predictedLTurnInput(error, yawSpeed, turnSign);
            if (simulateAirplane) {
                double effectiveEnginePower = boostedEnginePower(engineVehicle, enginePower, boostLevel);
                Vec3 forward = forwardFromRotation(yaw, pitch);
                velocity = simulateAirplaneVelocityStep(vehicle, velocity, forward, effectiveEnginePower,
                        friction, lift, horizontalDecay, verticalDecay, glideFactor);
                velocity = velocity.scale(brakeFactor);
                yaw -= yawSpeed * turnInput;
                pitch *= 1.0d - stabilizer;
                forward = forwardFromRotation(yaw, pitch);
                velocity = velocity.add(forward.scale(effectiveEnginePower * effectiveEnginePower * engineSpeed));
                enginePower += (0.0d - enginePower) * engineStep;
                boostLevel = nextPredictedBoostLevel(boostLevel, 0.0d);
            } else {
                velocity = velocity.scale(brakingDecay(vehicle));
                yaw -= yawSpeed * turnInput;
            }

            double signedStep = (firstAxisX ? velocity.x : velocity.z) * firstAxisSign;
            alongDistance += signedStep;
            turnInput += (desiredInput - turnInput) / L_NATIVE_INPUT_INTERPOLATION_STEPS;
            ticks = tick;
            if (turnComplete) {
                break;
            }
        }
        return new LTurnEstimate(Math.max(0.0d, alongDistance), ticks);
    }

    private static double predictedLTurnInput(float error, double yawSpeed, int turnSign) {
        double absoluteError = Math.abs(error);
        if (absoluteError <= yawSpeed || turnSign == 0 || lTurnSign(error) != turnSign) {
            return 0.0d;
        }
        return absoluteError <= yawSpeed * L_FINE_TURN_YAW_MULTIPLIER
                ? turnSign * L_FINE_TURN_INPUT : turnSign;
    }

    private static void tickLLockedLeg(VehicleEntity vehicle, CruiseVehicleAccess access,
                                        CruiseRoute route, boolean firstAxis) {
        float axisError = lAxisYawError(vehicle, route, firstAxis);
        float climbInput = altitudeInput(vehicle, navigationAltitude(route) - vehicle.getY());
        Vec3 reference = horizontalReferencePosition(vehicle);
        CruiseRoute.Waypoint lineTarget = route.getLNavigationTarget(reference.x, reference.z);
        float turn = lCenterlineTurnInput(vehicle, route, lineTarget, reference, firstAxis);
        LAlignmentPlan plan = L_ALIGNMENT_PLANS.get(vehicle);
        boolean dampingActive = plan != null
                && plan.stage() == route.getLoadingStage()
                && plan.firstAxis() == firstAxis
                && !plan.axisOnly();
        boolean settled = lHeadingSettled(vehicle, axisError) && !dampingActive;
        setBoosting(vehicle, access, settled && shouldBoost(vehicle, 0.0f,
                navigationAltitude(route) - vehicle.getY(), isAwayFromFinal(vehicle, route)));
        if (vehicle instanceof AirplaneEntity) {
            setCruiseInputs(vehicle, turn, 0.0f, -climbInput);
        } else {
            setCruiseInputs(vehicle, turn, climbInput, 1.0f);
        }
        if (vehicle instanceof EngineVehicle engineVehicle) {
            engineVehicle.setEngineTarget(1.0f);
        }
    }

    private static void tickLCenterlineCorrection(VehicleEntity vehicle, CruiseVehicleAccess access,
                                                   CruiseRoute route, boolean firstAxis) {
        Vec3 reference = horizontalReferencePosition(vehicle);
        CruiseRoute.Waypoint lineTarget = route.getLNavigationTarget(reference.x, reference.z);
        float turn = lCenterlineTurnInput(vehicle, route, lineTarget, reference, firstAxis);
        float climbInput = altitudeInput(vehicle, navigationAltitude(route) - vehicle.getY());
        boolean axisHeadingCorrection = lAxisHeadingCorrectionActive(vehicle, route, firstAxis);
        setBoosting(vehicle, access, false);
        if (vehicle instanceof AirplaneEntity) {
            setCruiseInputs(vehicle, turn, 0.0f, -climbInput);
        } else {
            setCruiseInputs(vehicle, turn, climbInput, axisHeadingCorrection ? 0.0f : 1.0f);
        }
        if (vehicle instanceof AirplaneEntity && vehicle instanceof EngineVehicle engineVehicle) {
            engineVehicle.setEngineTarget(axisHeadingCorrection ? 0.0f : 1.0f);
        }
    }

    private static void tickDirectLLineNavigation(VehicleEntity vehicle, CruiseVehicleAccess access,
                                                   CruiseRoute route) {
        Vec3 reference = horizontalReferencePosition(vehicle);
        CruiseRoute.Waypoint lineTarget = route.getLFinalLineTarget();
        float turn = lCenterlineTurnInput(vehicle, route, lineTarget, reference, false);
        float climbInput = altitudeInput(vehicle, navigationAltitude(route) - vehicle.getY());
        float axisError = lAxisYawError(vehicle, route, false);
        LAlignmentPlan plan = L_ALIGNMENT_PLANS.get(vehicle);
        boolean dampingActive = plan != null
                && plan.stage() == route.getLoadingStage()
                && !plan.firstAxis()
                && !plan.axisOnly();
        boolean settled = lHeadingSettled(vehicle, axisError) && !dampingActive;
        setBoosting(vehicle, access, settled && shouldBoost(vehicle, 0.0f,
                navigationAltitude(route) - vehicle.getY(), isAwayFromFinal(vehicle, route)));
        if (vehicle instanceof AirplaneEntity) {
            setCruiseInputs(vehicle, turn, 0.0f, -climbInput);
        } else {
            setCruiseInputs(vehicle, turn, climbInput, 1.0f);
        }
        if (vehicle instanceof EngineVehicle engineVehicle) {
            engineVehicle.setEngineTarget(1.0f);
        }
    }

    private static LAlignmentPlan getLAlignmentPlan(VehicleEntity vehicle, CruiseRoute route,
                                                     CruiseRoute.Waypoint lineTarget, Vec3 reference,
                                                     boolean firstAxis) {
        int stage = route.getLoadingStage();
        boolean xAxis = route.isLFirstAxisX() == firstAxis;
        int sign = firstAxis ? route.lFirstAxisSign() : route.lSecondAxisSign();
        double currentAlong = xAxis ? reference.x : reference.z;
        double speed = horizontalLength(vehicle.getDeltaMovement());
        double lateralError = lLateralError(route, lineTarget, reference, firstAxis);
        double predictedLateralError = lateralError
                - lLateralVelocity(vehicle, route, firstAxis) * L_CENTERLINE_DAMPING_HORIZON_TICKS;
        double lateralCorrection = predictedLateralError;
        double lookahead = Math.max(L_CENTERLINE_LOOKAHEAD,
                Math.max(Math.abs(lateralError), speed * L_CENTERLINE_LOOKAHEAD_TICKS));
        double predictedAlong = currentAlong + sign * lookahead;
        double targetLateral = (xAxis ? reference.z : reference.x) + lateralCorrection;
        double predictedX = xAxis ? predictedAlong : targetLateral;
        double predictedZ = xAxis ? targetLateral : predictedAlong;
        float targetYaw = (float) (Mth.atan2(-(predictedX - reference.x), predictedZ - reference.z)
                * 180.0d / Math.PI);
        return new LAlignmentPlan(stage, firstAxis, targetYaw, false, lateralError);
    }

    private static float lCenterlineTurnInput(VehicleEntity vehicle, CruiseRoute route,
                                              CruiseRoute.Waypoint lineTarget, Vec3 reference,
                                              boolean firstAxis) {
        float axisYaw = lAxisYaw(route, firstAxis);
        if (lineTarget == null) {
            return lFullTurnInput(vehicle, axisYaw);
        }
        double lateralError = lLateralError(route, lineTarget, reference, firstAxis);
        double lateralVelocity = lLateralVelocity(vehicle, route, firstAxis);
        double predictedLateralError = lateralError
                - lateralVelocity * L_CENTERLINE_DAMPING_HORIZON_TICKS;
        if (Math.abs(lateralError) <= L_CENTERLINE_LOCK_DISTANCE
                && Math.abs(predictedLateralError) <= L_CENTERLINE_HOLD_PREDICTED_ERROR
                && Math.abs(lateralVelocity) <= L_CENTERLINE_HOLD_LATERAL_SPEED
                && Math.abs(vehicle.pressingInterpolatedX.getSmooth()) <= L_CENTERLINE_HOLD_TURN_RATE) {
            return lAxisHoldInput(vehicle, route, firstAxis);
        }
        LAlignmentPlan correction = getLAlignmentPlan(vehicle, route, lineTarget, reference, firstAxis);
        L_ALIGNMENT_PLANS.put(vehicle, correction);
        return lContinuousTurnInput(vehicle, correction.targetYaw());
    }

    private static float lAxisHoldInput(VehicleEntity vehicle, CruiseRoute route,
                                        boolean firstAxis) {
        float axisYaw = lAxisYaw(route, firstAxis);
        LAlignmentPlan plan = L_ALIGNMENT_PLANS.get(vehicle);
        if (plan == null || plan.stage() != route.getLoadingStage()
                || plan.firstAxis() != firstAxis || !plan.axisOnly()) {
            L_ALIGNMENT_PLANS.put(vehicle,
                    new LAlignmentPlan(route.getLoadingStage(), firstAxis, axisYaw, true, 0.0d));
            L_TURN_STATES.remove(vehicle);
        }
        return lFullTurnInput(vehicle, axisYaw);
    }

    private static float lFullTurnInput(VehicleEntity vehicle, float targetYaw) {
        float error = Mth.wrapDegrees(vehicle.getYRot() - targetYaw);
        float smoothInput = vehicle.pressingInterpolatedX.getSmooth();
        LTurnState state = L_TURN_STATES.get(vehicle);
        if (state == null || Math.abs(Mth.wrapDegrees(state.targetYaw() - targetYaw)) > 0.5f) {
            int sign = lTurnSign(error);
            state = new LTurnState(targetYaw, sign, sign == 0 ? L_TURN_HOLD : L_TURN_APPROACH);
            L_TURN_STATES.put(vehicle, state);
        }
        float yawSpeed = (float) lYawSpeed(vehicle);
        float fineTurnLimit = yawSpeed * L_FINE_TURN_YAW_MULTIPLIER;
        if (Math.abs(error) <= yawSpeed) {
            L_TURN_STATES.put(vehicle, new LTurnState(targetYaw, 0, L_TURN_HOLD));
            L_PENDING_HEADING_SNAPS.put(vehicle, targetYaw);
            return traceLTurnResult(vehicle, targetYaw, error, smoothInput, 0.0f, "snap");
        }
        if (state.phase() == L_TURN_HOLD) {
            int sign = lTurnSign(error);
            state = new LTurnState(targetYaw, sign, sign == 0 ? L_TURN_HOLD : L_TURN_APPROACH);
            L_TURN_STATES.put(vehicle, state);
        }
        if (state.sign() == 0) {
            L_PENDING_HEADING_SNAPS.put(vehicle, targetYaw);
            return traceLTurnResult(vehicle, targetYaw, error, smoothInput, 0.0f, "snap-zero");
        }
        if (lTurnSign(error) != state.sign()) {
            L_TURN_STATES.put(vehicle, new LTurnState(targetYaw, 0, L_TURN_HOLD));
            L_PENDING_HEADING_SNAPS.put(vehicle, targetYaw);
            return traceLTurnResult(vehicle, targetYaw, error, smoothInput, 0.0f, "snap-crossed");
        }
        float input = Math.abs(error) <= fineTurnLimit
                ? state.sign() * L_FINE_TURN_INPUT
                : state.sign();
        return traceLTurnResult(vehicle, targetYaw, error, smoothInput, input,
                Math.abs(error) <= fineTurnLimit ? "fine" : "approach");
    }

    /**
     * Steers toward a moving centerline target without snapping the aircraft's
     * heading. The native input interpolation then supplies the remaining
     * smoothing while the target is recomputed from position and velocity.
     */
    private static float lContinuousTurnInput(VehicleEntity vehicle, float targetYaw) {
        float error = Mth.wrapDegrees(vehicle.getYRot() - targetYaw);
        float yawSpeed = (float) lYawSpeed(vehicle);
        float smoothInput = vehicle.pressingInterpolatedX.getSmooth();
        int sign = lTurnSign(error);
        L_TURN_STATES.put(vehicle, new LTurnState(targetYaw, sign,
                sign == 0 ? L_TURN_HOLD : L_TURN_APPROACH));
        float proportionalInput = Mth.clamp(
                error / (yawSpeed * L_FINE_TURN_YAW_MULTIPLIER), -1.0f, 1.0f);
        float input = Mth.clamp(proportionalInput
                - smoothInput * L_CONTINUOUS_TURN_RATE_DAMPING, -1.0f, 1.0f);
        boolean brakingResidual = lTurnSign(input) != 0 && lTurnSign(input) != sign;
        return traceLTurnResult(vehicle, targetYaw, error, smoothInput, input,
                brakingResidual ? "continuous-brake" : "continuous-damped");
    }

    private static int lTurnSign(float error) {
        return error == 0.0f ? 0 : (error < 0.0f ? -1 : 1);
    }

    private static float traceLTurnResult(VehicleEntity vehicle, float targetYaw, float error,
                                          float smoothInput, float turnInput, String reason) {
        if (!CruiseDebug.enabled()) {
            return turnInput;
        }
        LTurnState state = L_TURN_STATES.get(vehicle);
        int phase = state == null ? -1 : state.phase();
        int sign = state == null ? 0 : state.sign();
        LTurnDebugState previous = L_TURN_DEBUG_STATES.get(vehicle);
        int ticksSinceSample = previous == null ? 0 : previous.ticksSinceSample() + 1;
        boolean stateChanged = previous == null
                || previous.phase() != phase
                || previous.sign() != sign
                || Math.abs(Mth.wrapDegrees(previous.targetYaw() - targetYaw)) > 0.5f;
        if (stateChanged || ticksSinceSample >= L_TURN_DEBUG_SAMPLE_INTERVAL) {
            Vec3 velocity = vehicle.getDeltaMovement();
            ImmersiveAircraftCruise.LOGGER.debug(
                    "[CruiseLTurn] vehicle={} reason={} phase={} sign={} input={} yaw={} targetYaw={} error={} smoothInput={} yawSpeed={} pos={}/{}/{} velocity={}/{}/{}",
                    vehicle.getId(), reason, phase, sign, turnInput, vehicle.getYRot(), targetYaw, error,
                    smoothInput, lYawSpeed(vehicle), vehicle.getX(), vehicle.getY(), vehicle.getZ(),
                    velocity.x, velocity.y, velocity.z);
            ticksSinceSample = 0;
        }
        if (state == null) {
            L_TURN_DEBUG_STATES.remove(vehicle);
        } else {
            L_TURN_DEBUG_STATES.put(vehicle,
                    new LTurnDebugState(targetYaw, phase, sign, ticksSinceSample));
        }
        return turnInput;
    }

    private static void setLTurningInputs(VehicleEntity vehicle, CruiseVehicleAccess access, float turn) {
        setBoosting(vehicle, access, false);
        if (vehicle instanceof AirplaneEntity) {
            setCruiseInputs(vehicle, turn, -1.0f, 0.0f);
            if (vehicle instanceof EngineVehicle engineVehicle) {
                engineVehicle.setEngineTarget(0.0f);
            }
        } else {
            setCruiseInputs(vehicle, turn, 0.0f, 0.0f);
        }
    }

    private static void setLStage(VehicleEntity vehicle, CruiseRoute route, int stage, boolean clientSide) {
        if (route.getLoadingStage() == stage) {
            return;
        }
        L_ALIGNMENT_PLANS.remove(vehicle);
        L_TURN_STATES.remove(vehicle);
        L_PENDING_HEADING_SNAPS.remove(vehicle);
        route.setLoadingStage(stage);
        if (!clientSide) {
            CruiseModuleData.write(vehicle, route);
            syncRouteToClient(vehicle, route);
        }
    }

    private static float lAxisYawError(VehicleEntity vehicle, CruiseRoute route, boolean firstAxis) {
        int sign = firstAxis ? route.lFirstAxisSign() : route.lSecondAxisSign();
        double dx = firstAxis && route.isLFirstAxisX() || !firstAxis && !route.isLFirstAxisX() ? sign : 0.0d;
        double dz = dx == 0.0d ? sign : 0.0d;
        return yawError(vehicle.getYRot(), dx, dz);
    }

    private static float lAxisYaw(CruiseRoute route, boolean firstAxis) {
        int sign = firstAxis ? route.lFirstAxisSign() : route.lSecondAxisSign();
        double dx = firstAxis && route.isLFirstAxisX() || !firstAxis && !route.isLFirstAxisX() ? sign : 0.0d;
        double dz = dx == 0.0d ? sign : 0.0d;
        return (float) (Mth.atan2(-dx, dz) * 180.0d / Math.PI);
    }

    private static double lLateralError(CruiseRoute route, CruiseRoute.Waypoint lineTarget,
                                        Vec3 reference, boolean firstAxis) {
        if (lineTarget == null) {
            return Double.MAX_VALUE;
        }
        return route.isLFirstAxisX() == firstAxis
                ? lineTarget.z() - reference.z
                : lineTarget.x() - reference.x;
    }

    private static double lLateralVelocity(VehicleEntity vehicle, CruiseRoute route, boolean firstAxis) {
        Vec3 velocity = vehicle.getDeltaMovement();
        return route.isLFirstAxisX() == firstAxis ? velocity.z : velocity.x;
    }

    private static double lAlongDistance(CruiseRoute route, CruiseRoute.Waypoint target,
                                         Vec3 reference, boolean firstAxis) {
        if (target == null) {
            return 0.0d;
        }
        int sign = firstAxis ? route.lFirstAxisSign() : route.lSecondAxisSign();
        double delta = firstAxis && route.isLFirstAxisX() || !firstAxis && !route.isLFirstAxisX()
                ? target.x() - reference.x
                : target.z() - reference.z;
        return delta * sign;
    }

    private static float lAxisTurnInput(VehicleEntity vehicle, CruiseRoute route, boolean firstAxis) {
        return lFullTurnInput(vehicle, lAxisYaw(route, firstAxis));
    }

    private static boolean lHeadingSettled(VehicleEntity vehicle, float yawError) {
        return Math.abs(yawError) <= lYawSpeed(vehicle);
    }

    private static boolean lAxisHeadingCorrectionActive(VehicleEntity vehicle, CruiseRoute route,
                                                         boolean firstAxis) {
        LAlignmentPlan plan = L_ALIGNMENT_PLANS.get(vehicle);
        LTurnState turnState = L_TURN_STATES.get(vehicle);
        return plan != null
                && plan.stage() == route.getLoadingStage()
                && plan.firstAxis() == firstAxis
                && plan.axisOnly()
                && (turnState == null
                || turnState.phase() != L_TURN_HOLD
                || !lHeadingSettled(vehicle, lAxisYawError(vehicle, route, firstAxis)));
    }

    public static void serverProgressTick(VehicleEntity vehicle) {
        if (vehicle.level().isClientSide() || !(vehicle instanceof CruiseVehicleAccess access)) {
            return;
        }
        if (!CruiseModuleData.hasModule(vehicle)) {
            return;
        }
        CruiseRoute route = serverRoute(vehicle, access);
        if (route.isEnabled() && controllingServerPlayer(vehicle) == null) {
            stopNavigation(vehicle, access, route, LAST_PILOT.remove(vehicle), false);
            return;
        }
        if (!route.isEnabled()) {
            syncDisabledRouteToPilot(vehicle, route);
            return;
        }
        if (route.isHoldingPattern()) {
            setBoosting(vehicle, access, false);
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
        updateServerBoostingState(vehicle, access, route);
    }

    public static void rememberPilot(VehicleEntity vehicle, ServerPlayer player) {
        if (vehicle != null && player != null && isPilot(vehicle, player)
                && CruiseModuleData.hasModule(vehicle)) {
            LAST_PILOT.put(vehicle, player);
        }
    }

    /**
     * Clears a pilot's persisted navigation when the player connection goes away.
     * The vehicle may be outside the normal tick range, so this cannot rely on a
     * future vehicle tick to discover that its controlling passenger disappeared.
     */
    public static void handlePilotLoggedOut(ServerPlayer player) {
        if (player == null) {
            return;
        }
        for (Map.Entry<VehicleEntity, ServerPlayer> entry : new java.util.ArrayList<>(LAST_PILOT.entrySet())) {
            if (entry.getValue() != player) {
                continue;
            }
            VehicleEntity vehicle = entry.getKey();
            if (vehicle instanceof CruiseVehicleAccess access && CruiseModuleData.hasModule(vehicle)) {
                CruiseRoute route = serverRoute(vehicle, access);
                if (route.isEnabled()) {
                    stopNavigation(vehicle, access, route, null, false,
                            CruiseNavigationStopReason.NORMAL);
                }
            }
            LAST_PILOT.remove(vehicle);
            LAST_DISABLED_SYNC_PILOT.remove(vehicle);
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

    /**
     * Clears only runtime state derived from a route definition change. Progress
     * and the takeoff anchor remain valid unless the caller explicitly refreshes
     * the route.
     */
    public static void reconcileRouteDefinitionChange(VehicleEntity vehicle,
                                                       CruiseRoute previous,
                                                       CruiseRoute updated) {
        if (previous == null || updated == null
                || previous.getSelectedRoute() != updated.getSelectedRoute()) {
            return;
        }
        CruiseRoute.RouteEntry before = previous.getSelectedEntry();
        CruiseRoute.RouteEntry after = updated.getSelectedEntry();
        if (!navigationDefinitionChanged(before, after)) {
            return;
        }

        updated.setLoadingStage(CruiseRoute.L_STAGE_AXIS_ALIGN);
        updated.setLFirstLineCoordinate(null);
        if (before.defaultAltitude() != after.defaultAltitude()
                && updated.getCurrentIndex() == 0) {
            updated.setInitialAltitudeReached(false);
        }
        L_ALIGNMENT_PLANS.remove(vehicle);
        L_TURN_STATES.remove(vehicle);
        L_PENDING_HEADING_SNAPS.remove(vehicle);
        ALTITUDE_MEMORY.remove(vehicle);
        LANDING_ACTIVE.remove(vehicle);
        FAST_LANDING_FINAL_BRAKE_ACTIVE.remove(vehicle);
        POST_LANDING_BRAKE.remove(vehicle);
        clearLandingAssistState(vehicle);
        PRELOAD_ACCELERATION_PERMITTED.remove(vehicle);
        LAST_SENT_ACCELERATION_PERMITTED.remove(vehicle);
    }

    private static boolean navigationDefinitionChanged(CruiseRoute.RouteEntry before,
                                                        CruiseRoute.RouteEntry after) {
        if (before.defaultAltitude() != after.defaultAltitude()
                || before.cruiseMode() != after.cruiseMode()
                || before.loadingMode() != after.loadingMode()
                || before.landingMode() != after.landingMode()
                || !Objects.equals(before.landingAltitude(), after.landingAltitude())) {
            return true;
        }
        List<CruiseRoute.Waypoint> beforeWaypoints = before.waypoints();
        List<CruiseRoute.Waypoint> afterWaypoints = after.waypoints();
        if (beforeWaypoints.size() != afterWaypoints.size()) {
            return true;
        }
        for (int index = 0; index < beforeWaypoints.size(); index++) {
            CruiseRoute.Waypoint first = beforeWaypoints.get(index);
            CruiseRoute.Waypoint second = afterWaypoints.get(index);
            if (first.x() != second.x() || first.z() != second.z()
                    || !Objects.equals(first.altitude(), second.altitude())) {
                return true;
            }
        }
        return false;
    }

    public static CruiseRoute routeForOpeningScreen(VehicleEntity vehicle, int clientSelectedRoute, int clientCurrentIndex,
                                                    boolean clientHoldingPattern, boolean clientInitialAltitudeReached,
                                                    CruiseRoute.Waypoint clientStartPoint) {
        if (!(vehicle instanceof CruiseVehicleAccess access)) {
            return CruiseModuleData.read(vehicle);
        }
        return CruiseModuleData.findModule(vehicle)
                .map(stack -> {
                    String moduleId = CruiseModuleData.moduleId(vehicle, stack);
                    boolean sameModule = moduleId.equals(ACTIVE_MODULE_ID.get(vehicle));
                    CruiseRoute loaded = CruiseModuleData.read(stack);
                    if (sameModule && clientSelectedRoute == loaded.getSelectedRoute()
                            && loaded.mergeProgressForward(clientCurrentIndex, clientHoldingPattern,
                            clientInitialAltitudeReached, clientStartPoint)) {
                        CruiseModuleData.write(stack, loaded);
                    }
                    access.iacruise$setRoute(loaded.copy());
                    ACTIVE_MODULE.put(vehicle, stack);
                    ACTIVE_MODULE_ID.put(vehicle, moduleId);
                    return loaded;
                })
                .orElseGet(CruiseRoute::empty);
    }

    private static CruiseRoute serverRoute(VehicleEntity vehicle, CruiseVehicleAccess access) {
        return CruiseModuleData.findModule(vehicle)
                .map(stack -> {
                    String moduleId = CruiseModuleData.moduleId(vehicle, stack);
                    boolean sameModule = moduleId.equals(ACTIVE_MODULE_ID.get(vehicle));
                    CruiseRoute loaded = CruiseModuleData.read(stack);
                    if (sameModule) {
                        CruiseRoute cached = access.iacruise$getRoute();
                        if (loaded.mergeProgressForwardFrom(cached)) {
                            CruiseModuleData.write(stack, loaded);
                        }
                    }
                    access.iacruise$setRoute(loaded);
                    ACTIVE_MODULE.put(vehicle, stack);
                    ACTIVE_MODULE_ID.put(vehicle, moduleId);
                    return loaded;
                })
                .orElseGet(() -> {
                    ACTIVE_MODULE.remove(vehicle);
                    CruiseRoute cached = access.iacruise$getRoute();
                    cached.stopNavigation();
                    return cached;
                });
    }

    public static float getPowerMultiplier(EngineVehicle vehicle) {
        if (!(vehicle instanceof CruiseVehicleAccess access)) {
            return 1.0f;
        }
        if (!canApplyCruiseModifiers(vehicle, access)) {
            return 1.0f;
        }
        if (usesControlledBoost(vehicle)) {
            return 1.0f;
        }
        return powerMultiplier(access, boostLevel(vehicle, access));
    }

    public static float getFuelMultiplier(EngineVehicle vehicle) {
        if (!(vehicle instanceof CruiseVehicleAccess access)) {
            return 1.0f;
        }
        if (!canApplyCruiseModifiers(vehicle, access)) {
            return 1.0f;
        }
        return fuelMultiplier(access, boostLevel(vehicle, access));
    }

    private static void tickHoldingPattern(VehicleEntity vehicle, CruiseRoute route) {
        if (!route.isEnabled()) {
            return;
        }
        LANDING_ACTIVE.remove(vehicle);
        FAST_LANDING_FINAL_BRAKE_ACTIVE.remove(vehicle);
        clearLandingAssistState(vehicle);
        if (isVerticalAircraft(vehicle)) {
            CruiseRoute.Waypoint finalTarget = route.getFinalTarget();
            if (finalTarget != null) {
                tickRotorcraftHoverControl(vehicle, finalTarget.x() + 0.5, finalTarget.z() + 0.5,
                        route.getFinalFlightAltitude() - vehicle.getY(), false);
                return;
            }
        }
        float turn = -1.0f;
        float forward = 1.0f;
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
        if (route.isLShapedSingleMode() && route.getLoadingStage() < CruiseRoute.L_STAGE_FINAL_LEG
                && route.shouldUseLShaped(vehicle.getX(), vehicle.getZ())) {
            return false;
        }
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
            if (!isVerticalAircraft(vehicle)) {
                return tickFastestLanding(vehicle, access, route, waypoint, horizontalDistance, dx, dz, clientSide);
            }
            Vec3 referencePosition = horizontalReferencePosition(vehicle);
            dx = waypoint.x() + 0.5 - referencePosition.x;
            dz = waypoint.z() + 0.5 - referencePosition.z;
            horizontalDistance = Math.sqrt(dx * dx + dz * dz);
            if (tickVerticalLanding(vehicle, access, route, waypoint, horizontalDistance, clientSide)) {
                return true;
            }
            return false;
        }
        return tickFastestLanding(vehicle, access, route, waypoint, horizontalDistance, dx, dz, clientSide);
    }

    private static boolean tickVerticalLanding(VehicleEntity vehicle, CruiseVehicleAccess access, CruiseRoute route, CruiseRoute.Waypoint waypoint,
                                               double horizontalDistance, boolean clientSide) {
        return tickRotorcraftVerticalLanding(vehicle, access, route, waypoint, horizontalDistance, clientSide);
    }

    private static boolean tickRotorcraftHoldingApproach(VehicleEntity vehicle, CruiseVehicleAccess access, CruiseRoute route,
                                                         CruiseRoute.Waypoint waypoint, double horizontalDistance) {
        if (!isVerticalAircraft(vehicle)
                || !route.isFinalTarget()
                || route.getEffectiveLandingMode() != CruiseRoute.LandingMode.HOLDING_PATTERN
                || horizontalDistance > WAYPOINT_RADIUS) {
            return false;
        }
        setBoosting(vehicle, access, false);
        tickRotorcraftHoverControl(vehicle, waypoint.x() + 0.5, waypoint.z() + 0.5,
                route.getFinalFlightAltitude() - vehicle.getY(), false);
        return true;
    }

    private static boolean tickRotorcraftVerticalLanding(VehicleEntity vehicle, CruiseVehicleAccess access, CruiseRoute route,
                                                        CruiseRoute.Waypoint waypoint, double horizontalDistance,
                                                        boolean clientSide) {
        if (horizontalDistance > WAYPOINT_RADIUS && !LANDING_ACTIVE.containsKey(vehicle)) {
            return false;
        }
        Vec3 referencePosition = horizontalReferencePosition(vehicle);
        double dx = waypoint.x() + 0.5 - referencePosition.x;
        double dz = waypoint.z() + 0.5 - referencePosition.z;
        float yawError = yawError(vehicle.getYRot(), dx, dz);
        if (!LANDING_ACTIVE.containsKey(vehicle) && Math.abs(yawError) > FAST_LANDING_APPROACH_YAW_LIMIT) {
            return false;
        }
        setBoosting(vehicle, access, false);
        boolean aligned = horizontalDistance <= VERTICAL_AIRCRAFT_LANDING_HORIZONTAL_RADIUS
                && horizontalSpeed(vehicle) <= LANDING_VERTICAL_STOP_SPEED;
        if (!LANDING_ACTIVE.containsKey(vehicle) && !aligned) {
            tickRotorcraftHoverControl(vehicle, waypoint.x() + 0.5, waypoint.z() + 0.5,
                    route.getFinalFlightAltitude() - vehicle.getY(), false, true);
            return true;
        }

        LANDING_ACTIVE.put(vehicle, true);
        if (handleNavigationHorizontalCollision(vehicle, access, route, clientSide)) {
            return true;
        }
        tickRotorcraftHoverControl(vehicle, waypoint.x() + 0.5, waypoint.z() + 0.5,
                landingAltitudeError(vehicle, route.getFinalAltitude()), true, true);
        if (isLandingComplete(vehicle, waypoint, route.getFinalAltitude(), VERTICAL_AIRCRAFT_LANDING_HORIZONTAL_RADIUS,
                LANDING_ALTITUDE_RADIUS, LANDING_VERTICAL_STOP_SPEED)) {
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

        float yawError = yawError(vehicle.getYRot(), dx, dz);
        if (!LANDING_ACTIVE.containsKey(vehicle)
                && !FAST_LANDING_FINAL_BRAKE_ACTIVE.containsKey(vehicle)
                && vehicle instanceof AirplaneEntity
                && vehicle.onGround()
                && (horizontalDistance <= FAST_LANDING_GROUND_APPROACH_RADIUS
                || altitudeError <= FAST_LANDING_COMPLETION_ALTITUDE_RADIUS)
                && horizontalDistance <= FAST_LANDING_ALIGN_RADIUS) {
            stopBoostingImmediately(vehicle, access);
            if (handleNavigationHorizontalCollision(vehicle, access, route, clientSide)) {
                return true;
            }
            if (isFastLandingReadyForPostBrake(vehicle, landingTarget.x(), landingTarget.z(), route.getFinalAltitude())) {
                beginPostLandingBrake(vehicle, access, route, clientSide);
                return true;
            }
            tickFastLandingGroundGuard(vehicle, yawError, horizontalDistance, dx, dz);
            return true;
        }
        if (!LANDING_ACTIVE.containsKey(vehicle)
                && !FAST_LANDING_FINAL_BRAKE_ACTIVE.containsKey(vehicle)
                && Math.abs(yawError) > FAST_LANDING_APPROACH_YAW_LIMIT) {
            if (tickFastLandingAlignGuard(vehicle, yawError, horizontalDistance)) {
                stopBoostingImmediately(vehicle, access);
                return true;
            }
            return false;
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
            clampLandingPitch(vehicle);
            updateFastLandingPassState(vehicle, dx, dz, horizontalDistance);
            if (finalBrake && handleNavigationHorizontalCollision(vehicle, access, route, clientSide)) {
                return true;
            }
            boolean groundApproach = finalBrake && vehicle.onGround();
            boolean groundBrakeZone = groundApproach && isAirplaneLandingBrakeZone(vehicle, horizontalDistance);
            float forwardInput = groundApproach
                    ? (groundBrakeZone ? 0.0f : airplaneGroundApproachInput(vehicle, dx, dz, horizontalDistance))
                    : pitchInput;
            float brakeInput = airplaneLandingBrakeInput(vehicle, finalBrake, horizontalDistance);
            if (groundApproach && !groundBrakeZone && !isAirplaneGroundEngineCleared(vehicle)) {
                brakeInput = AIRPLANE_GROUND_CLEAR_THROTTLE_INPUT;
                forwardInput = 0.0f;
            } else if (groundApproach && forwardInput < -0.01f) {
                turn = airplaneGroundReverseTurnInput(yawError, horizontalDistance);
            } else {
                if (groundApproach && isAirplaneCapturedOvershoot(vehicle, horizontalDistance)) {
                    turn = Mth.clamp(turn, -AIRPLANE_GROUND_TURN_INPUT, AIRPLANE_GROUND_TURN_INPUT);
                }
                if (groundApproach && isAirplaneWideOvershoot(vehicle, horizontalDistance)) {
                    turn = Mth.clamp(turn, -AIRPLANE_GROUND_TURNAROUND_INPUT, AIRPLANE_GROUND_TURNAROUND_INPUT);
                }
            }
            setCruiseInputs(vehicle, turn, brakeInput, forwardInput);
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
        float yawError = yawError(vehicle.getYRot(), dx, dz);
        if (!LANDING_ACTIVE.containsKey(vehicle)
                && !FAST_LANDING_FINAL_BRAKE_ACTIVE.containsKey(vehicle)
                && Math.abs(yawError) > FAST_LANDING_APPROACH_YAW_LIMIT) {
            return false;
        }
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

        float turn = horizontalDistance > FAST_LANDING_TURN_RADIUS
                ? fastLandingTurnInput(vehicle, yawError, horizontalDistance)
                : 0.0f;
        boolean alignmentGuard = shouldRotorcraftLandingAlignGuard(horizontalDistance, yawError);
        boolean reversing = !alignmentGuard && finalBrake && Math.abs(yawError) > ROTORCRAFT_FAST_LANDING_BACKWARD_YAW_LIMIT;
        float forwardInput = alignmentGuard ? 0.0f : rotorcraftForwardInput(vehicle, dx, dz, horizontalDistance, finalBrake, reversing);
        float verticalInput = descentCommitted || finalBrake
                ? rotorcraftDescentInput(vehicle, altitudeError)
                : altitudeInput(vehicle, route.getTargetAltitude() - rotorcraftAltitudeY(vehicle));

        if (finalBrake || alignmentGuard) {
            stopBoostingImmediately(vehicle, access);
        } else {
            setBoosting(vehicle, access, true);
        }
        if (finalBrake && handleNavigationHorizontalCollision(vehicle, access, route, clientSide)) {
            return true;
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
        if (handleNavigationHorizontalCollision(vehicle, access, route, clientSide)) {
            return;
        }
        if (tickPostLandingStopped(vehicle, access, route, clientSide)) {
            return;
        }
        if (vehicle instanceof AirplaneEntity) {
            setCruiseInputs(vehicle, 0.0f, -1.0f, 0.0f);
        } else {
            setCruiseInputs(vehicle, 0.0f, 0.0f, 0.0f);
            vehicle.setDeltaMovement(brakedVelocity(vehicle, 0.86d));
        }
        if (!(vehicle instanceof AirplaneEntity) && vehicle instanceof EngineVehicle engineVehicle) {
            engineVehicle.setEngineTarget(Math.max(0.0f, engineVehicle.getEngineTarget() - 0.1f));
        }
        POST_LANDING_BRAKE.put(vehicle, ticks - 1);
    }

    private static boolean handleNavigationHorizontalCollision(VehicleEntity vehicle, CruiseVehicleAccess access,
                                                               CruiseRoute route, boolean clientSide) {
        if (!vehicle.horizontalCollision) {
            return false;
        }
        stopNavigation(vehicle, access, route, controllingServerPlayer(vehicle), clientSide,
                CruiseNavigationStopReason.COLLISION);
        if (clientSide) {
            syncStoppedNavigationToServer(vehicle, route);
        }
        return true;
    }

    private static boolean tickPostLandingStopped(VehicleEntity vehicle, CruiseVehicleAccess access,
                                                  CruiseRoute route, boolean clientSide) {
        if (horizontalSpeed(vehicle) > FAST_LANDING_GROUND_STOP_SPEED) {
            POST_LANDING_STOPPED.remove(vehicle);
            return false;
        }
        int ticks = POST_LANDING_STOPPED.getOrDefault(vehicle, 0) + 1;
        if (ticks < POST_LANDING_STOPPED_TICKS) {
            POST_LANDING_STOPPED.put(vehicle, ticks);
            return false;
        }
        finishLanding(vehicle, access, route, clientSide);
        return true;
    }

    private static float airplaneGroundEngineTarget(VehicleEntity vehicle) {
        return vehicle instanceof EngineVehicle engineVehicle ? engineVehicle.getEngineTarget() : 0.0f;
    }

    private static boolean isAirplaneGroundEngineCleared(VehicleEntity vehicle) {
        return airplaneGroundEngineTarget(vehicle) <= AIRPLANE_GROUND_ENGINE_CLEAR_THRESHOLD;
    }

    private static float airplaneGroundTaxiThrottleInput(VehicleEntity vehicle) {
        double speed = horizontalSpeed(vehicle);
        if (speed > FAST_LANDING_GROUND_TAXI_TARGET_SPEED + FAST_LANDING_GROUND_TAXI_SPEED_TOLERANCE) {
            return FAST_LANDING_GROUND_TAXI_BRAKE_INPUT;
        }
        if (speed < FAST_LANDING_GROUND_TAXI_TARGET_SPEED - FAST_LANDING_GROUND_TAXI_SPEED_TOLERANCE) {
            return FAST_LANDING_GROUND_TAXI_THROTTLE_INPUT;
        }
        return 0.0f;
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
                && vehicle.onGround()
                && horizontalSpeed(vehicle) <= stopSpeed;
    }

    private static boolean isFastLandingReadyForPostBrake(VehicleEntity vehicle, double targetX, double targetZ, double targetAltitude) {
        double horizontalRadius = landingHorizontalRadius(vehicle, FAST_LANDING_COMPLETION_HORIZONTAL_RADIUS);
        double horizontalDistance = horizontalDistance(vehicle, targetX, targetZ);
        if (horizontalDistance > horizontalRadius || horizontalSpeed(vehicle) > FAST_LANDING_POST_BRAKE_STOP_SPEED) {
            return false;
        }
        return vehicle.onGround();
    }

    private static boolean isRotorcraftFastLandingComplete(VehicleEntity vehicle, double targetX, double targetZ, double targetAltitude) {
        Vec3 referencePosition = horizontalReferencePosition(vehicle);
        double dx = targetX - referencePosition.x;
        double dz = targetZ - referencePosition.z;
        return Math.sqrt(dx * dx + dz * dz) <= VERTICAL_AIRCRAFT_LANDING_HORIZONTAL_RADIUS
                && vehicle.onGround()
                && horizontalSpeed(vehicle) <= LANDING_VERTICAL_STOP_SPEED
                && vehicle.getControllingPassenger() != null;
    }

    private static void finishLanding(VehicleEntity vehicle, CruiseVehicleAccess access, CruiseRoute route, boolean clientSide) {
        route.setCurrentIndex(route.getSelectedEntry().waypoints().size() - 1);
        route.setHoldingPattern(true);
        setCruiseInputs(vehicle, 0.0f, 0.0f, 0.0f);
        if (isVerticalAircraft(vehicle)) {
            vehicle.setDeltaMovement(Vec3.ZERO);
        } else {
            vehicle.setDeltaMovement(vehicle.getDeltaMovement().multiply(0.2d, 0.2d, 0.2d));
        }
        LANDING_ACTIVE.remove(vehicle);
        POST_LANDING_BRAKE.remove(vehicle);
        clearLandingAssistState(vehicle);
        stopNavigation(vehicle, access, route, controllingServerPlayer(vehicle), clientSide,
                CruiseNavigationStopReason.FINISHED);
        if (clientSide) {
            syncFinishedNavigationToServer(vehicle, route);
        }
    }

    private static void syncFinishedNavigationToServer(VehicleEntity vehicle, CruiseRoute route) {
        if (vehicle.getControllingPassenger() instanceof Player player && player.isLocalPlayer()) {
            CruiseNetwork.CHANNEL.sendToServer(new StopCruiseNavigationPacket(vehicle.getId(), route,
                    CruiseNavigationStopReason.FINISHED));
        }
    }

    private static void syncStoppedNavigationToServer(VehicleEntity vehicle, CruiseRoute route) {
        if (vehicle.getControllingPassenger() instanceof Player player && player.isLocalPlayer()) {
            CruiseNetwork.CHANNEL.sendToServer(new StopCruiseNavigationPacket(vehicle.getId(), route,
                    CruiseNavigationStopReason.COLLISION));
        }
    }

    private static void clearLandingAssistState(VehicleEntity vehicle) {
        FAST_LANDING_LAST_OFFSET.remove(vehicle);
        FAST_LANDING_PASSED_TARGET.remove(vehicle);
        POST_LANDING_STOPPED.remove(vehicle);
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
        double descentDistance = simulatedRotorcraftForwardDistance(vehicle, descentTravelTicks,
                effectiveAccelerationTarget(vehicle, 1.0f));
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
        double maxSpeed = CruiseConfig.rotorcraftSpeedLimit() / 20.0d;
        double distance = 0.0d;
        for (int tick = 0; tick < tickLimit; tick++) {
            double stepRatio = Math.min(1.0d, ticks - tick);
            if (stepRatio <= 0.0d) {
                break;
            }
            velocity = velocity.scale(horizontalDecay);
            double thrust = Math.pow(enginePower, 5.0d) * engineSpeed;
            Vec3 thrustVelocity = forward.scale(thrust);
            velocity = velocity.add(thrustVelocity.scale(controlledThrustMultiplier(vehicle, boostLevel)));
            if (maxSpeed > 0.0d) {
                double horizontalSpeed = horizontalLength(velocity);
                if (horizontalSpeed > maxSpeed) {
                    velocity = velocity.scale(maxSpeed / horizontalSpeed);
                }
            }
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

    private static float airplaneGroundApproachInput(VehicleEntity vehicle, double dx, double dz, double horizontalDistance) {
        if (Boolean.TRUE.equals(FAST_LANDING_PASSED_TARGET.get(vehicle))
                && horizontalDistance > AIRPLANE_GROUND_APPROACH_CAPTURE_RADIUS) {
            return AIRPLANE_GROUND_TURNAROUND_INPUT;
        }
        Vec3 forward = forwardFromRotation(vehicle.getYRot(), 0.0d);
        forward = new Vec3(forward.x, 0.0d, forward.z);
        if (forward.lengthSqr() <= 1.0E-8d) {
            return 0.0f;
        }
        forward = forward.normalize();
        double forwardError = forward.x * dx + forward.z * dz;
        if (Math.abs(forwardError) <= AIRPLANE_GROUND_APPROACH_DEAD_ZONE) {
            return 0.0f;
        }
        return forwardError > 0.0d ? AIRPLANE_GROUND_APPROACH_INPUT : -AIRPLANE_GROUND_APPROACH_INPUT;
    }

    private static float airplaneGroundReverseTurnInput(float yawError, double horizontalDistance) {
        float magnitude = Math.abs(yawError);
        double lateralError = Math.sin(Math.toRadians(magnitude)) * horizontalDistance;
        if (magnitude <= AIRPLANE_GROUND_REVERSE_YAW_DEAD_ZONE
                || lateralError <= AIRPLANE_GROUND_REVERSE_LATERAL_DEAD_ZONE) {
            return 0.0f;
        }
        float input = Mth.clamp((magnitude - AIRPLANE_GROUND_REVERSE_YAW_DEAD_ZONE) / 90.0f,
                0.0f, AIRPLANE_GROUND_REVERSE_TURN_MAX_INPUT);
        return -Math.signum(yawError) * input;
    }

    private static float airplaneLandingBrakeInput(VehicleEntity vehicle, boolean finalBrake, double horizontalDistance) {
        if (!finalBrake) {
            return 0.0f;
        }
        return !vehicle.onGround() || isAirplaneLandingBrakeZone(vehicle, horizontalDistance) ? -1.0f : 0.0f;
    }

    private static void tickFastLandingGroundGuard(VehicleEntity vehicle, float yawError,
                                                   double horizontalDistance, double dx, double dz) {
        updateFastLandingPassState(vehicle, dx, dz, horizontalDistance);

        boolean groundBrakeZone = isAirplaneLandingBrakeZone(vehicle, horizontalDistance);
        float turn = horizontalDistance > FAST_LANDING_TURN_RADIUS ? fastLandingTurnInput(vehicle, yawError, horizontalDistance) : 0.0f;
        float throttleInput = 0.0f;
        float forwardInput = 0.0f;
        if (groundBrakeZone || horizontalDistance <= FAST_LANDING_GROUND_APPROACH_RADIUS
                && (!isAirplaneGroundEngineCleared(vehicle)
                || horizontalSpeed(vehicle) > FAST_LANDING_GROUND_APPROACH_BRAKE_SPEED)) {
            throttleInput = AIRPLANE_GROUND_CLEAR_THROTTLE_INPUT;
        } else if (!groundBrakeZone) {
            if (horizontalDistance <= FAST_LANDING_GROUND_APPROACH_RADIUS) {
                forwardInput = airplaneGroundApproachInput(vehicle, dx, dz, horizontalDistance);
                if (forwardInput < -0.01f) {
                    turn = airplaneGroundReverseTurnInput(yawError, horizontalDistance);
                } else if (isAirplaneCapturedOvershoot(vehicle, horizontalDistance)) {
                    turn = Mth.clamp(turn, -AIRPLANE_GROUND_TURN_INPUT, AIRPLANE_GROUND_TURN_INPUT);
                } else if (isAirplaneWideOvershoot(vehicle, horizontalDistance)) {
                    turn = Mth.clamp(turn, -AIRPLANE_GROUND_TURNAROUND_INPUT, AIRPLANE_GROUND_TURNAROUND_INPUT);
                }
            } else if (Math.abs(yawError) <= FAST_LANDING_GROUND_TAXI_YAW_LIMIT) {
                throttleInput = airplaneGroundTaxiThrottleInput(vehicle);
                forwardInput = throttleInput < -0.01f ? 0.0f : FAST_LANDING_GROUND_TAXI_FORWARD_INPUT;
            }
        }

        setCruiseInputs(vehicle, turn, throttleInput, forwardInput);
    }

    private static boolean tickFastLandingAlignGuard(VehicleEntity vehicle, float yawError, double horizontalDistance) {
        if (!(vehicle instanceof AirplaneEntity) || vehicle.onGround() || horizontalDistance > FAST_LANDING_ALIGN_RADIUS) {
            return false;
        }
        float turn = turnInput(vehicle, yawError);
        setCruiseInputs(vehicle, turn, -0.45f, 0.0f);
        return true;
    }

    private static boolean tickRotorcraftLandingAlignGuard(VehicleEntity vehicle, CruiseVehicleAccess access, CruiseRoute route,
                                                           double horizontalDistance, float yawError, float verticalInput) {
        if (!isVerticalAircraft(vehicle)
                || !route.isFinalTarget()
                || route.getEffectiveLandingMode() == CruiseRoute.LandingMode.HOLDING_PATTERN
                || !shouldRotorcraftLandingAlignGuard(horizontalDistance, yawError)) {
            return false;
        }
        stopBoostingImmediately(vehicle, access);
        float turn = turnInput(vehicle, yawError);
        setCruiseInputs(vehicle, turn, verticalInput, 0.0f);
        if (turn != 0.0f) {
            vehicle.setYRot(vehicle.getYRot() - turn * 1.5f);
        }
        if (vehicle instanceof EngineVehicle engineVehicle && engineVehicle.getEngineTarget() < 1.0f) {
            engineVehicle.setEngineTarget(1.0f);
        }
        return true;
    }

    private static boolean shouldRotorcraftLandingAlignGuard(double horizontalDistance, float yawError) {
        return horizontalDistance <= FAST_LANDING_ALIGN_RADIUS
                && Math.abs(yawError) > FAST_LANDING_APPROACH_YAW_LIMIT;
    }

    private static boolean isAirplaneLandingBrakeZone(VehicleEntity vehicle, double horizontalDistance) {
        double completionRadius = landingHorizontalRadius(vehicle, FAST_LANDING_COMPLETION_HORIZONTAL_RADIUS);
        return horizontalDistance <= completionRadius;
    }

    private static void updateFastLandingPassState(VehicleEntity vehicle, double dx, double dz, double horizontalDistance) {
        Vec3 currentOffset = new Vec3(dx, 0.0d, dz);
        Vec3 previousOffset = FAST_LANDING_LAST_OFFSET.put(vehicle, currentOffset);
        if (previousOffset == null || Boolean.TRUE.equals(FAST_LANDING_PASSED_TARGET.get(vehicle))) {
            return;
        }
        Vec3 travel = currentOffset.subtract(previousOffset);
        double travelSqr = horizontalLengthSqr(travel);
        if (travelSqr <= 1.0E-8d) {
            return;
        }
        double closestT = Mth.clamp(-dotHorizontal(previousOffset, travel) / travelSqr, 0.0d, 1.0d);
        Vec3 closest = previousOffset.add(travel.scale(closestT));
        if (horizontalLengthSqr(closest) <= AIRPLANE_GROUND_APPROACH_CAPTURE_RADIUS * AIRPLANE_GROUND_APPROACH_CAPTURE_RADIUS
                && dotHorizontal(previousOffset, currentOffset) <= 0.0d) {
            FAST_LANDING_PASSED_TARGET.put(vehicle, true);
        }
    }

    private static boolean isAirplaneCapturedOvershoot(VehicleEntity vehicle, double horizontalDistance) {
        return Boolean.TRUE.equals(FAST_LANDING_PASSED_TARGET.get(vehicle))
                && horizontalDistance <= AIRPLANE_GROUND_APPROACH_CAPTURE_RADIUS;
    }

    private static boolean isAirplaneWideOvershoot(VehicleEntity vehicle, double horizontalDistance) {
        return Boolean.TRUE.equals(FAST_LANDING_PASSED_TARGET.get(vehicle))
                && horizontalDistance > AIRPLANE_GROUND_APPROACH_CAPTURE_RADIUS;
    }

    private static double dotHorizontal(Vec3 a, Vec3 b) {
        return a.x * b.x + a.z * b.z;
    }

    private static double horizontalLengthSqr(Vec3 value) {
        return value.x * value.x + value.z * value.z;
    }

    private static void tickRotorcraftHoverControl(VehicleEntity vehicle, double targetX, double targetZ,
                                                   double altitudeError, boolean preciseAltitude) {
        tickRotorcraftHoverControl(vehicle, targetX, targetZ, altitudeError, preciseAltitude, false);
    }

    private static void tickRotorcraftHoverControl(VehicleEntity vehicle, double targetX, double targetZ,
                                                   double altitudeError, boolean preciseAltitude, boolean guardForwardAlignment) {
        Vec3 referencePosition = horizontalReferencePosition(vehicle);
        double dx = targetX - referencePosition.x;
        double dz = targetZ - referencePosition.z;
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        float yawError = yawError(vehicle.getYRot(), dx, dz);
        boolean alignmentGuard = guardForwardAlignment && shouldRotorcraftLandingAlignGuard(horizontalDistance, yawError);
        boolean reversing = !alignmentGuard && Math.abs(yawError) > ROTORCRAFT_FAST_LANDING_BACKWARD_YAW_LIMIT;
        boolean aligned = horizontalDistance <= VERTICAL_AIRCRAFT_LANDING_HORIZONTAL_RADIUS
                && horizontalSpeed(vehicle) <= LANDING_VERTICAL_STOP_SPEED;
        float turn = horizontalDistance > FAST_LANDING_TURN_RADIUS
                ? fastLandingTurnInput(vehicle, yawError, horizontalDistance)
                : 0.0f;
        float forwardInput = alignmentGuard ? 0.0f : rotorcraftForwardInput(vehicle, dx, dz, horizontalDistance, true, reversing);
        float verticalInput = preciseAltitude ? rotorcraftDescentInput(vehicle, altitudeError) : altitudeInput(vehicle, altitudeError);

        setCruiseInputs(vehicle, reversing ? 0.0f : turn, verticalInput, aligned ? 0.0f : forwardInput);
        if (!reversing && turn != 0.0f) {
            vehicle.setYRot(vehicle.getYRot() - turn * 1.5f);
        }
        if (aligned) {
            brakeRotorcraftHorizontalVelocity(vehicle);
        } else {
            vehicle.setDeltaMovement(brakedVelocity(vehicle, 0.94d));
        }
        if (vehicle instanceof EngineVehicle engineVehicle && engineVehicle.getEngineTarget() < 1.0f) {
            engineVehicle.setEngineTarget(1.0f);
        }
    }

    private static float rotorcraftDescentInput(VehicleEntity vehicle, double altitudeError) {
        double verticalSpeed = vehicle.getDeltaMovement().y;
        if (vehicle.onGround()) {
            return rotorcraftFastLandingVerticalInput(vehicle, 0.0f);
        }
        if (altitudeError >= -FAST_LANDING_COMPLETION_ALTITUDE_RADIUS) {
            return rotorcraftTouchdownInput(vehicle, verticalSpeed);
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

    private static float rotorcraftTouchdownInput(VehicleEntity vehicle, double verticalSpeed) {
        if (verticalSpeed < -ROTORCRAFT_TOUCHDOWN_MAX_DESCENT_SPEED) {
            return rotorcraftFastLandingVerticalInput(vehicle, ROTORCRAFT_TOUCHDOWN_BRAKE_INPUT);
        }
        return rotorcraftFastLandingVerticalInput(vehicle, ROTORCRAFT_TOUCHDOWN_DESCENT_INPUT);
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
        boolean accelerationPermitted = accelerationPermit(vehicle);
        boolean positivePowerMode = cruiseMode(vehicle).powerBonus() > 0.0f;
        float boostTarget = positivePowerMode && !accelerationPermitted ? 0.0f : 1.0f;
        DescentEstimate descentEstimate = descentEstimate(vehicle, activeDescent, boostTarget);
        if (positivePowerMode && accelerationPermitted) {
            for (int iteration = 0; iteration < 3; iteration++) {
                boostTarget = fastLandingBoostTarget(availableDescentDistance, currentSpeed, activeDescent, descentEstimate.ticks());
                descentEstimate = descentEstimate(vehicle, activeDescent, boostTarget);
            }
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
        if (engineVehicle instanceof CruiseVehicleAccess access) {
            return enginePower * powerMultiplier(access, Mth.clamp((float) boostLevel, 0.0f, 1.0f));
        }
        return enginePower;
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

    private static float powerMultiplier(CruiseVehicleAccess access, float boostLevel) {
        float multiplier = 1.0f + cruiseMode(access).powerBonus() * Mth.clamp(boostLevel, 0.0f, 1.0f);
        return Math.max(0.0f, multiplier);
    }

    private static float fuelMultiplier(CruiseVehicleAccess access, float boostLevel) {
        float multiplier = 1.0f + cruiseMode(access).fuelBonus() * Mth.clamp(boostLevel, 0.0f, 1.0f);
        return Math.max(0.0f, multiplier);
    }

    private static double controlledThrustMultiplier(VehicleEntity vehicle, double boostLevel) {
        return Math.max(0.0d, 1.0d + controlledPowerBonus(vehicle, boostLevel));
    }

    private static double controlledPowerBonus(VehicleEntity vehicle, double boostLevel) {
        if (!(vehicle instanceof EngineVehicle engineVehicle)
                || !(vehicle instanceof CruiseVehicleAccess access)
                || !canApplyCruiseModifiers(engineVehicle, access)) {
            return 0.0d;
        }
        return cruiseMode(vehicle).powerBonus() * Mth.clamp((float) boostLevel, 0.0f, 1.0f);
    }

    private static CruiseRoute.CruiseMode cruiseMode(Entity entity) {
        if (entity instanceof CruiseVehicleAccess access) {
            return cruiseMode(access);
        }
        return CruiseRoute.CruiseMode.SUPER_ACCELERATION;
    }

    private static CruiseRoute.CruiseMode cruiseMode(CruiseVehicleAccess access) {
        CruiseRoute route = access.iacruise$getRoute();
        return route == null ? CruiseRoute.CruiseMode.SUPER_ACCELERATION : route.getCruiseMode();
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

    private static boolean shouldBoost(VehicleEntity vehicle, float yawError,
                                       double altitudeError, boolean awayFromFinal) {
        if (!awayFromFinal) {
            return false;
        }
        float yaw = Math.abs(yawError);
        double altitude = Math.abs(altitudeError);
        CruiseVehicleAccess access = (CruiseVehicleAccess) vehicle;
        if (BOOST_REQUESTED.getOrDefault(vehicle, access.iacruise$isBoosting())) {
            return yaw <= BOOST_EXIT_YAW && altitude <= BOOST_EXIT_ALTITUDE;
        }
        return yaw <= BOOST_ENTER_YAW && altitude <= BOOST_ENTER_ALTITUDE;
    }

    private static boolean preloadAccelerationGateApplies(CruiseRoute route) {
        return route.isEnabled()
                && route.getSelectedEntry().loadingMode() != CruiseRoute.RouteLoadingMode.VANILLA
                && route.getCruiseMode().powerBonus() > 0.0f;
    }

    private static boolean accelerationPermit(VehicleEntity vehicle) {
        if (!(vehicle instanceof CruiseVehicleAccess access)) {
            return true;
        }
        CruiseRoute route = access.iacruise$getRoute();
        return route == null || !preloadAccelerationGateApplies(route)
                || Boolean.TRUE.equals(PRELOAD_ACCELERATION_PERMITTED.get(vehicle));
    }

    private static float effectiveAccelerationTarget(VehicleEntity vehicle, float requestedTarget) {
        return cruiseMode(vehicle).powerBonus() > 0.0f && !accelerationPermit(vehicle)
                ? 0.0f : requestedTarget;
    }

    static void updatePreloadAccelerationPermit(VehicleEntity vehicle, ServerPlayer pilot,
                                                CruiseRoute route) {
        if (vehicle.level().isClientSide()) {
            return;
        }
        boolean permitted = !preloadAutoDeceleration
                || !preloadAccelerationGateApplies(route)
                || CruiseChunkSendScheduler.isAccelerationReady(vehicle, route);
        PRELOAD_ACCELERATION_PERMITTED.put(vehicle, permitted);
        if (pilot == null) {
            LAST_SENT_ACCELERATION_PERMITTED.remove(vehicle);
            return;
        }
        Boolean previous = LAST_SENT_ACCELERATION_PERMITTED.put(vehicle, permitted);
        if (previous == null || previous != permitted) {
            CruiseNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> pilot),
                    new UpdateCruiseAccelerationPermitPacket(vehicle.getId(), permitted));
            CruiseDebug.debug(ImmersiveAircraftCruise.LOGGER,
                    "[CruiseChunks] acceleration permit changed: vehicleId={}, pilot={}, permitted={}, tick={}",
                    vehicle.getId(), pilot.getScoreboardName(), permitted, vehicle.tickCount);
        }
    }

    static void synchronizePreloadAccelerationPermit(VehicleEntity vehicle, ServerPlayer pilot) {
        CruiseVehicleAccess access = (CruiseVehicleAccess) vehicle;
        CruiseRoute route = serverRoute(vehicle, access);
        boolean permitted = !preloadAccelerationGateApplies(route)
                || CruiseChunkSendScheduler.isAccelerationReady(vehicle, route);
        PRELOAD_ACCELERATION_PERMITTED.put(vehicle, permitted);
        LAST_SENT_ACCELERATION_PERMITTED.put(vehicle, permitted);
        CruiseNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> pilot),
                new UpdateCruiseAccelerationPermitPacket(vehicle.getId(), permitted));
        CruiseDebug.debug(ImmersiveAircraftCruise.LOGGER,
                "[CruiseChunks] acceleration permit synchronized: vehicleId={}, pilot={}, permitted={}, tick={}",
                vehicle.getId(), pilot.getScoreboardName(), permitted, vehicle.tickCount);
    }

    public static void updatePreloadAccelerationPermit(VehicleEntity vehicle) {
        CruiseVehicleAccess access = (CruiseVehicleAccess) vehicle;
        updatePreloadAccelerationPermit(vehicle, controllingServerPlayer(vehicle), serverRoute(vehicle, access));
    }

    public static void updateClientAccelerationPermit(VehicleEntity vehicle, boolean permitted) {
        PRELOAD_ACCELERATION_PERMITTED.put(vehicle, permitted);
    }

    private static void updateServerBoostingState(VehicleEntity vehicle, CruiseVehicleAccess access, CruiseRoute route) {
        if (vehicle.level().isClientSide()) {
            return;
        }
        if (!route.isEnabled() || route.isHoldingPattern() || !route.hasTarget()) {
            setBoosting(vehicle, access, false);
            return;
        }
        if (vehicle instanceof EngineVehicle engineVehicle) {
            boolean requested = BOOST_REQUESTED.getOrDefault(vehicle, access.iacruise$isBoosting());
            float target = requested ? BOOST_TARGET_LEVEL.getOrDefault(engineVehicle, 1.0f) : 0.0f;
            boolean effective = requested && accelerationPermit(vehicle);
            access.iacruise$setBoosting(effective);
            updateBoostLevel(engineVehicle, effective ? target : 0.0f, effective);
        }
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
        if (shouldApplyPreloadAccelerationBrake(vehicle)) {
            movementY = -1.0f;
            movementZ = 0.0f;
        }
        if (movementY < -0.01f) {
            AUTO_BRAKE_INPUT.put(vehicle, true);
        }
        vehicle.setInputs(movementX, movementY, movementZ);
    }

    /**
     * A failed preload permit removes only the extra acceleration request; the
     * aircraft still needs a native brake input so it does not coast into the
     * first unloaded route slice. The original boost request remains intact and
     * is restored automatically once the ten-slice permit is acknowledged.
     */
    private static boolean shouldApplyPreloadAccelerationBrake(VehicleEntity vehicle) {
        if (!(vehicle instanceof AirplaneEntity)
                || !BOOST_REQUESTED.getOrDefault(vehicle, false)
                || accelerationPermit(vehicle)) {
            return false;
        }
        if (!(vehicle instanceof CruiseVehicleAccess access)) {
            return false;
        }
        CruiseRoute route = access.iacruise$getRoute();
        return route != null && preloadAccelerationGateApplies(route);
    }

    public static void clearCruiseInputs(VehicleEntity vehicle) {
        AUTO_BRAKE_INPUT.remove(vehicle);
        vehicle.setInputs(0.0f, 0.0f, 0.0f);
    }

    private static void setBoosting(VehicleEntity vehicle, CruiseVehicleAccess access, boolean boosting) {
        setBoosting(vehicle, access, boosting, boosting ? 1.0f : 0.0f);
    }

    private static void setBoosting(VehicleEntity vehicle, CruiseVehicleAccess access, boolean boosting, float targetBoostLevel) {
        BOOST_REQUESTED.put(vehicle, boosting);
        boolean effective = boosting;
        if (vehicle instanceof EngineVehicle engineVehicle) {
            float target = boosting ? Mth.clamp(targetBoostLevel, 0.0f, 1.0f) : 0.0f;
            if (boosting) {
                BOOST_TARGET_LEVEL.put(engineVehicle, target);
            } else {
                BOOST_TARGET_LEVEL.remove(engineVehicle);
            }
            effective = boosting && accelerationPermit(vehicle);
            access.iacruise$setBoosting(effective);
            updateBoostLevel(engineVehicle, effective ? target : 0.0f, effective);
        } else {
            access.iacruise$setBoosting(boosting);
        }
        syncLocalPilotBoostingState(vehicle, boosting, targetBoostLevel);
    }

    private static void stopBoostingImmediately(VehicleEntity vehicle, CruiseVehicleAccess access) {
        BOOST_REQUESTED.put(vehicle, false);
        access.iacruise$setBoosting(false);
        if (vehicle instanceof EngineVehicle engineVehicle) {
            BOOST_LEVEL.remove(engineVehicle);
            BOOST_TARGET_LEVEL.remove(engineVehicle);
        }
        syncLocalPilotBoostingState(vehicle, false, 0.0f);
    }

    private static void stopNavigationEffects(VehicleEntity vehicle, CruiseVehicleAccess access) {
        BOOST_REQUESTED.remove(vehicle);
        PRELOAD_ACCELERATION_PERMITTED.remove(vehicle);
        LAST_SENT_ACCELERATION_PERMITTED.remove(vehicle);
        access.iacruise$setBoosting(false);
        if (vehicle instanceof EngineVehicle engineVehicle) {
            BOOST_LEVEL.remove(engineVehicle);
            BOOST_TARGET_LEVEL.remove(engineVehicle);
        }
        LAST_PILOT.remove(vehicle);
        TURN_MEMORY.remove(vehicle);
        L_ALIGNMENT_PLANS.remove(vehicle);
        L_TURN_STATES.remove(vehicle);
        ALTITUDE_MEMORY.remove(vehicle);
        LANDING_ACTIVE.remove(vehicle);
        FAST_LANDING_FINAL_BRAKE_ACTIVE.remove(vehicle);
        AUTO_BRAKE_INPUT.remove(vehicle);
        POST_LANDING_BRAKE.remove(vehicle);
        clearLandingAssistState(vehicle);
        LAST_CLIENT_BOOST_SYNC.remove(vehicle);
        syncLocalPilotBoostingState(vehicle, false, 0.0f);
    }

    private static void syncLocalPilotBoostingState(VehicleEntity vehicle, boolean boosting, float targetBoostLevel) {
        if (!vehicle.level().isClientSide()) {
            return;
        }
        if (!(vehicle.getControllingPassenger() instanceof Player player) || !player.isLocalPlayer()) {
            return;
        }
        int levelStep = boostSyncLevelStep(boosting, targetBoostLevel);
        BoostSyncState previous = LAST_CLIENT_BOOST_SYNC.get(vehicle);
        boolean stateChanged = previous == null || previous.boosting() != boosting;
        boolean levelChanged = previous == null || previous.levelStep() != levelStep;
        boolean intervalElapsed = previous == null || vehicle.tickCount - previous.tick() >= BOOST_SYNC_INTERVAL_TICKS;
        if (!stateChanged && (!levelChanged || !intervalElapsed)) {
            return;
        }
        LAST_CLIENT_BOOST_SYNC.put(vehicle, new BoostSyncState(boosting, levelStep, vehicle.tickCount));
        CruiseNetwork.CHANNEL.sendToServer(new UpdateCruiseBoostPacket(vehicle.getId(), boosting, boostLevelFromStep(levelStep)));
    }

    private static int boostSyncLevelStep(boolean boosting, float targetBoostLevel) {
        if (!boosting) {
            return 0;
        }
        return Mth.clamp(Math.round(targetBoostLevel * BOOST_SYNC_LEVEL_STEPS), 0, BOOST_SYNC_LEVEL_STEPS);
    }

    private static float boostLevelFromStep(int levelStep) {
        return Mth.clamp(levelStep, 0, BOOST_SYNC_LEVEL_STEPS) / (float) BOOST_SYNC_LEVEL_STEPS;
    }

    public static void stopNavigation(VehicleEntity vehicle, CruiseRoute route, ServerPlayer messagePlayer) {
        stopNavigation(vehicle, route, messagePlayer, CruiseNavigationStopReason.NORMAL);
    }

    public static void stopNavigation(VehicleEntity vehicle, CruiseRoute route, ServerPlayer messagePlayer,
                                      CruiseNavigationStopReason reason) {
        if (vehicle instanceof CruiseVehicleAccess access) {
            stopNavigation(vehicle, access, route, messagePlayer, vehicle.level().isClientSide(), reason);
        }
    }

    public static void updatePilotBoostingState(VehicleEntity vehicle, boolean boosting, float boostLevel) {
        if (vehicle.level().isClientSide() || !(vehicle instanceof CruiseVehicleAccess access)) {
            return;
        }
        CruiseRoute route = serverRoute(vehicle, access);
        boolean active = boosting && route.isEnabled() && !route.isHoldingPattern() && route.hasTarget();
        setBoosting(vehicle, access, active, active ? boostLevel : 0.0f);
    }

    public static void updatePilotSpeed(VehicleEntity vehicle, float speed) {
        if (vehicle.level().isClientSide()) {
            return;
        }
        PILOT_SPEEDS.put(vehicle, new PilotSpeedSample(Mth.clamp(speed, 0.0f, PILOT_SPEED_MAX),
                vehicle.level().getServer().getTickCount()));
    }

    private static void stopNavigation(VehicleEntity vehicle, CruiseVehicleAccess access, CruiseRoute route,
                                       ServerPlayer messagePlayer, boolean clientSide) {
        stopNavigation(vehicle, access, route, messagePlayer, clientSide, CruiseNavigationStopReason.NORMAL);
    }

    private static void stopNavigation(VehicleEntity vehicle, CruiseVehicleAccess access, CruiseRoute route,
                                       ServerPlayer messagePlayer, boolean clientSide,
                                       CruiseNavigationStopReason reason) {
        route.stopNavigation();
        L_PENDING_HEADING_SNAPS.remove(vehicle);
        stopNavigationEffects(vehicle, access);
        clearCruiseInputs(vehicle);
        CruiseNavigationStopReason effectiveReason = reason == null ? CruiseNavigationStopReason.NORMAL : reason;
        if (!clientSide) {
            CruiseModuleData.write(vehicle, route);
            access.iacruise$setRoute(route.copy());
            syncRouteToClient(vehicle, route);
            if (messagePlayer != null) {
                if (!vehicle.getPassengers().contains(messagePlayer)) {
                    CruiseNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> messagePlayer),
                            new UpdateCruiseRoutePacket(vehicle.getId(), route.copy()));
                }
                messagePlayer.displayClientMessage(Component.translatable(effectiveReason.messageKey()), true);
            }
        }
    }

    private static void pauseNavigationForNoFuel(VehicleEntity vehicle, CruiseVehicleAccess access, CruiseRoute route, boolean clientSide) {
        ServerPlayer pilot = controllingServerPlayer(vehicle);
        stopNavigation(vehicle, access, route, pilot != null ? pilot : LAST_PILOT.remove(vehicle), clientSide);
    }

    private static ServerPlayer controllingServerPlayer(VehicleEntity vehicle) {
        return vehicle.getControllingPassenger() instanceof ServerPlayer player ? player : null;
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
        if (vehicle.level().isClientSide() && !isLocalClientPilot(vehicle)) {
            CONTROLLER_VELOCITY_BEFORE.remove(vehicle);
            return;
        }
        if (!(vehicle instanceof EngineVehicle engineVehicle) || !usesControlledBoost(engineVehicle)) {
            CONTROLLER_VELOCITY_BEFORE.remove(vehicle);
            return;
        }
        if (!(vehicle instanceof CruiseVehicleAccess access) || !canApplyCruiseModifiers(engineVehicle, access)) {
            CONTROLLER_VELOCITY_BEFORE.remove(vehicle);
            return;
        }
        float level = BOOST_LEVEL.getOrDefault(engineVehicle, 0.0f);
        if (Math.abs(controlledPowerBonus(vehicle, level)) <= 1.0E-6d) {
            CONTROLLER_VELOCITY_BEFORE.remove(vehicle);
            return;
        }
        CONTROLLER_VELOCITY_BEFORE.put(vehicle, vehicle.getDeltaMovement());
    }

    public static void afterUpdateController(VehicleEntity vehicle) {
        if (vehicle.level().isClientSide() && !isLocalClientPilot(vehicle)) {
            CONTROLLER_VELOCITY_BEFORE.remove(vehicle);
            return;
        }
        snapLHeadingAfterController(vehicle);
        clampLandingPitch(vehicle);
        if (!(vehicle instanceof EngineVehicle engineVehicle) || !usesControlledBoost(engineVehicle)) {
            CONTROLLER_VELOCITY_BEFORE.remove(vehicle);
            return;
        }
        if (!(vehicle instanceof CruiseVehicleAccess access) || !canApplyCruiseModifiers(engineVehicle, access)) {
            CONTROLLER_VELOCITY_BEFORE.remove(vehicle);
            return;
        }
        Vec3 before = CONTROLLER_VELOCITY_BEFORE.remove(vehicle);
        if (before == null) {
            clampRotorcraftSpeed(vehicle, access);
            return;
        }
        float level = BOOST_LEVEL.getOrDefault(engineVehicle, 0.0f);
        double powerBonus = controlledPowerBonus(vehicle, level);
        if (Math.abs(powerBonus) <= 1.0E-6d) {
            clampRotorcraftSpeed(vehicle, access);
            return;
        }
        Vec3 after = vehicle.getDeltaMovement();
        Vec3 controllerDelta = after.subtract(before).multiply(1.0d, 0.0d, 1.0d);
        if (controllerDelta.lengthSqr() > 1.0E-8d) {
            vehicle.setDeltaMovement(after.add(controllerDelta.scale(powerBonus)));
        }
        clampRotorcraftSpeed(vehicle, access);
    }

    private static void clampRotorcraftSpeed(VehicleEntity vehicle, CruiseVehicleAccess access) {
        if (!(vehicle instanceof Rotorcraft) || !canApplyCruiseModifiers((EngineVehicle) vehicle, access)
                || !access.iacruise$getRoute().hasTarget()) {
            return;
        }
        double limitBlocksPerSecond = CruiseConfig.rotorcraftSpeedLimit();
        if (limitBlocksPerSecond <= 0.0d) {
            return;
        }
        double maxSpeed = limitBlocksPerSecond / 20.0d;
        Vec3 velocity = vehicle.getDeltaMovement();
        double horizontalSpeed = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        if (horizontalSpeed <= maxSpeed || horizontalSpeed <= 1.0E-8d) {
            return;
        }
        double factor = maxSpeed / horizontalSpeed;
        vehicle.setDeltaMovement(velocity.x * factor, velocity.y, velocity.z * factor);
    }

    /**
     * The aircraft's native controller applies the interpolated turn input
     * after the navigation tick has chosen a command. When an L leg is
     * already inside one native turn tick, finish the small remaining angle
     * after that controller has run so its residual input cannot turn the
     * craft back on the next frame.
     */
    private static void snapLHeadingAfterController(VehicleEntity vehicle) {
        if (!(vehicle instanceof CruiseVehicleAccess access)) {
            return;
        }
        CruiseRoute route = access.iacruise$getRoute();
        boolean lShapedRoute = route != null && route.shouldUseLShaped(vehicle.getX(), vehicle.getZ());
        boolean directLLine = route != null && route.isLongLRouteCandidate() && !lShapedRoute;
        if (route == null || !route.isEnabled() || (!lShapedRoute && !directLLine)) {
            return;
        }
        Float pendingHeadingSnap = L_PENDING_HEADING_SNAPS.remove(vehicle);
        if (pendingHeadingSnap != null) {
            float snapError = Mth.wrapDegrees(vehicle.getYRot() - pendingHeadingSnap);
            vehicle.setYRot(vehicle.getYRot() - snapError);
            return;
        }
        LAlignmentPlan plan = L_ALIGNMENT_PLANS.get(vehicle);
        if (plan != null && plan.axisOnly() && route.getLoadingStage() == plan.stage()) {
            float planYawError = Mth.wrapDegrees(vehicle.getYRot() - plan.targetYaw());
            if (Math.abs(planYawError) <= lYawSpeed(vehicle)) {
                vehicle.setYRot(vehicle.getYRot() - planYawError);
                return;
            }
        }
        int stage = route.getLoadingStage();
        boolean firstAxis;
        if (stage == CruiseRoute.L_STAGE_FIRST_LEG) {
            CruiseRoute.Waypoint target = route.getLNavigationTarget(vehicle.getX(), vehicle.getZ());
            if (target == null || Math.abs(lLateralError(route, target,
                    horizontalReferencePosition(vehicle), true)) > L_CENTERLINE_LOCK_DISTANCE) {
                return;
            }
            firstAxis = true;
        } else if (stage == CruiseRoute.L_STAGE_CORNER_TURN) {
            firstAxis = false;
        } else if (stage == CruiseRoute.L_STAGE_FINAL_LEG) {
            CruiseRoute.Waypoint target = route.getLNavigationTarget(vehicle.getX(), vehicle.getZ());
            if (target == null || Math.abs(lLateralError(route, target,
                    horizontalReferencePosition(vehicle), false)) > L_CENTERLINE_LOCK_DISTANCE) {
                return;
            }
            firstAxis = false;
        } else {
            return;
        }
        // A dynamic oblique correction must be allowed to work through its
        // lateral velocity; only the axis-only plan may snap to parallel.
        plan = L_ALIGNMENT_PLANS.get(vehicle);
        if (plan == null || !plan.axisOnly()
                || route.getLoadingStage() != plan.stage()
                || plan.firstAxis() != firstAxis) {
            return;
        }
        float yawError = lAxisYawError(vehicle, route, firstAxis);
        if (Math.abs(yawError) <= lYawSpeed(vehicle)) {
            vehicle.setYRot(vehicle.getYRot() - yawError);
        }
    }

    public static void clampLandingPitch(VehicleEntity vehicle) {
        if (vehicle instanceof AirplaneEntity
                && (LANDING_ACTIVE.containsKey(vehicle)
                || FAST_LANDING_FINAL_BRAKE_ACTIVE.containsKey(vehicle)
                || POST_LANDING_BRAKE.containsKey(vehicle))) {
            vehicle.setXRot(Mth.clamp(vehicle.getXRot(), -AIRPLANE_LANDING_PITCH_LIMIT, AIRPLANE_LANDING_PITCH_LIMIT));
        }
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

    public static void syncRouteToPassengers(VehicleEntity vehicle, CruiseRoute route) {
        for (Entity passenger : vehicle.getPassengers()) {
            if (passenger instanceof ServerPlayer player) {
                CruiseNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                        new UpdateCruiseRoutePacket(vehicle.getId(), route.copy()));
            }
        }
    }

    /**
     * Immersive Aircraft calculates upgrade-derived properties from each
     * client's local vehicle inventory.  Refresh every onboard client when
     * navigation starts so passengers do not need to open the native aircraft
     * screen to make the installed upgrades take effect locally.
     */
    public static void syncVehicleInventoryToPassengers(VehicleEntity vehicle) {
        for (Entity passenger : vehicle.getPassengers()) {
            if (passenger instanceof ServerPlayer player) {
                syncVehicleInventoryToPlayer(vehicle, player);
            }
        }
    }

    public static void syncVehicleInventoryToPlayer(VehicleEntity vehicle, ServerPlayer player) {
        if (!(vehicle instanceof InventoryVehicleEntity inventoryVehicle)) {
            return;
        }
        SyncVehicleInventoryPacket packet = SyncVehicleInventoryPacket.fromVehicle(inventoryVehicle);
        CruiseNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                packet);
        CruiseDebug.debug(ImmersiveAircraftCruise.LOGGER,
                "[CruiseChunks] vehicle inventory sync: vehicleId={}, player={}, entries={}",
                vehicle.getId(), player.getScoreboardName(), packet.entries().size());
    }

    private static void syncDisabledRouteToPilot(VehicleEntity vehicle, CruiseRoute route) {
        ServerPlayer pilot = controllingServerPlayer(vehicle);
        if (pilot == null) {
            LAST_DISABLED_SYNC_PILOT.remove(vehicle);
            return;
        }
        if (LAST_DISABLED_SYNC_PILOT.get(vehicle) == pilot) {
            return;
        }
        CruiseNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> pilot),
                new UpdateCruiseRoutePacket(vehicle.getId(), route.copy()));
        LAST_DISABLED_SYNC_PILOT.put(vehicle, pilot);
    }

    private static void syncRouteToClient(VehicleEntity vehicle, CruiseRoute route) {
        syncRouteToPassengers(vehicle, route);
    }

    private static void syncFuelInfo(EngineVehicle engineVehicle) {
        VehicleEntity vehicle = engineVehicle;
        List<SlotDescription> slots = engineVehicle.getInventoryDescription().getSlots(VehicleInventoryDescription.BOILER);
        int[] fuel = engineVehicle instanceof EngineVehicleAccessor accessor
                ? accessor.immersive_aircraft_cruise$getFuel()
                : new int[0];
        int slotIndex = displayedFuelSlot(engineVehicle, slots, fuel);
        int storedFuel = slotIndex >= 0 && slotIndex < fuel.length ? Math.max(0, fuel[slotIndex]) : 0;
        FuelSlotDisplay display = fuelDisplayForSlot(engineVehicle, slots, slotIndex);
        ItemStack icon = display.icon();
        if (icon.isEmpty() && engineVehicle instanceof CruiseFuelIconAccess fuelIconAccess) {
            icon = fuelIconAccess.iacruise$getBurningFuelIcon();
        }
        if (storedFuel <= 0 && display.pendingFuel() <= 0) {
            icon = ItemStack.EMPTY;
        }
        float consumption = Math.max(0.0f, engineVehicle.getFuelConsumption());
        int remainingTicks = consumption <= 0.0f ? -1 : clampTicks((storedFuel + display.pendingFuel()) / (double) consumption);
        float speed = pilotSpeed(vehicle);
        boolean boosting = engineVehicle instanceof CruiseVehicleAccess access && access.iacruise$isBoosting();
        CruiseFuelInfo fuelInfo = new CruiseFuelInfo(display.amountText(), remainingTicks, icon, speed, boosting);
        for (Entity passenger : vehicle.getPassengers()) {
            if (passenger instanceof ServerPlayer player) {
                awardSpeedAdvancement(player, speed);
                String recipientRole = vehicle.getControllingPassenger() == player ? "pilot" : "passenger";
                CruiseDebug.info(ImmersiveAircraftCruise.LOGGER,
                        "[CruiseFuelSync] send: serverTick={}, vehicleId={}, vehicleTick={}, recipient={}, "
                                + "role={}, amount={}, remainingTicks={}, speed={}, boosting={}, icon={}",
                        vehicle.level().getServer().getTickCount(), vehicle.getId(), vehicle.tickCount,
                        player.getScoreboardName(), recipientRole, fuelInfo.amountText(),
                        fuelInfo.remainingTicks(), fuelInfo.speed(), fuelInfo.boosting(), describeFuelIcon(icon));
                CruiseNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                        new UpdateCruiseFuelPacket(vehicle.getId(),
                                vehicle.getX(), vehicle.getY(), vehicle.getZ(), fuelInfo));
            }
        }
    }

    private static String describeFuelIcon(ItemStack icon) {
        return icon.isEmpty()
                ? "empty"
                : BuiltInRegistries.ITEM.getKey(icon.getItem()) + "x" + icon.getCount();
    }

    private static void awardSpeedAdvancement(ServerPlayer player, float speed) {
        if (speed < SPEED_ADVANCEMENT_THRESHOLD) {
            return;
        }
        Advancement advancement = player.server.getAdvancements().getAdvancement(SPEED_ADVANCEMENT_ID);
        if (advancement != null && !player.getAdvancements().getOrStartProgress(advancement).isDone()) {
            player.getAdvancements().award(advancement, "speed_117");
        }
    }

    private static float pilotSpeed(VehicleEntity vehicle) {
        PilotSpeedSample sample = PILOT_SPEEDS.get(vehicle);
        if (sample == null
                || vehicle.level().getServer().getTickCount() - sample.serverTick() >= PILOT_SPEED_TIMEOUT_TICKS) {
            return 0.0f;
        }
        return sample.speed();
    }

    private static int displayedFuelSlot(EngineVehicle engineVehicle, List<SlotDescription> slots, int[] fuel) {
        int size = Math.min(slots.size(), fuel.length);
        for (int i = 0; i < size; i++) {
            if (fuel[i] > 0) {
                return i;
            }
        }
        for (int i = 0; i < slots.size(); i++) {
            ItemStack stack = engineVehicle.getInventory().getItem(slots.get(i).index());
            if (immersive_aircraft.util.Utils.getFuelTime(stack) > 0) {
                return i;
            }
        }
        return -1;
    }

    private static FuelSlotDisplay fuelDisplayForSlot(EngineVehicle engineVehicle, List<SlotDescription> slots, int slotIndex) {
        if (slotIndex < 0 || slotIndex >= slots.size()) {
            return FuelSlotDisplay.empty();
        }
        ItemStack stack = engineVehicle.getInventory().getItem(slots.get(slotIndex).index());
        if (stack.isEmpty()) {
            return FuelSlotDisplay.empty();
        }
        int fuelTime = immersive_aircraft.util.Utils.getFuelTime(stack);
        FluidStack fluid = containedFluid(stack);
        if (!fluid.isEmpty()) {
            long pendingFuel = fluidPendingFuel(stack, fluid, fuelTime);
            return new FuelSlotDisplay(formatFluidAmount(fluid), pendingFuel, stack.copyWithCount(1));
        }
        if (fuelTime <= 0) {
            return FuelSlotDisplay.empty();
        }
        return new FuelSlotDisplay(Integer.toString(stack.getCount()), (long) fuelTime * stack.getCount(), stack.copyWithCount(1));
    }

    private static long fluidPendingFuel(ItemStack stack, FluidStack fluid, int fuelTime) {
        if (fuelTime <= 0) {
            return 0L;
        }
        int consumedPerUse = fluidConsumedByCraftingUse(stack, fluid);
        if (consumedPerUse <= 0) {
            return (long) fuelTime * stack.getCount();
        }
        return (long) fuelTime * (fluid.getAmount() / consumedPerUse);
    }

    private static int fluidConsumedByCraftingUse(ItemStack stack, FluidStack fluid) {
        ItemStack single = stack.copyWithCount(1);
        if (!single.getItem().hasCraftingRemainingItem(single)) {
            return 0;
        }
        ItemStack remaining = single.getItem().getCraftingRemainingItem(single);
        FluidStack remainingFluid = containedFluid(remaining);
        if (remainingFluid.isEmpty()) {
            return fluid.getAmount();
        }
        if (remainingFluid.getFluid() != fluid.getFluid() || !Objects.equals(remainingFluid.getTag(), fluid.getTag())) {
            return 0;
        }
        return Math.max(0, fluid.getAmount() - remainingFluid.getAmount());
    }

    private static FluidStack containedFluid(ItemStack stack) {
        if (stack.isEmpty()) {
            return FluidStack.EMPTY;
        }
        return stack.getCapability(ForgeCapabilities.FLUID_HANDLER_ITEM)
                .map(CruiseController::firstFluid)
                .orElse(FluidStack.EMPTY);
    }

    private static FluidStack firstFluid(IFluidHandlerItem handler) {
        for (int i = 0; i < handler.getTanks(); i++) {
            FluidStack fluid = handler.getFluidInTank(i);
            if (!fluid.isEmpty()) {
                return fluid.copy();
            }
        }
        return FluidStack.EMPTY;
    }

    private static String formatFluidAmount(FluidStack fluid) {
        return fluid.getAmount() + " mB";
    }

    private static int clampTicks(double ticks) {
        if (ticks >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return Math.max(0, (int) Math.round(ticks));
    }

    private record FuelSlotDisplay(String amountText, long pendingFuel, ItemStack icon) {
        private static FuelSlotDisplay empty() {
            return new FuelSlotDisplay("0", 0L, ItemStack.EMPTY);
        }
    }

    private record BoostSyncState(boolean boosting, int levelStep, int tick) {
    }

}
