package com.g1739.immersiveaircraftcruise.cruise;

import com.g1739.immersiveaircraftcruise.mixin.EngineVehicleAccessor;
import com.g1739.immersiveaircraftcruise.network.CruiseNetwork;
import com.g1739.immersiveaircraftcruise.network.UpdateCruiseFuelPacket;
import com.g1739.immersiveaircraftcruise.network.UpdateCruiseRoutePacket;
import immersive_aircraft.entity.AirplaneEntity;
import immersive_aircraft.entity.EngineVehicle;
import immersive_aircraft.entity.Rotorcraft;
import immersive_aircraft.entity.VehicleEntity;
import immersive_aircraft.entity.inventory.VehicleInventoryDescription;
import immersive_aircraft.entity.inventory.slots.SlotDescription;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;

import java.util.Map;
import java.util.WeakHashMap;

public final class CruiseController {
    private static final double WAYPOINT_RADIUS = 16.0;
    private static final double FINAL_ACCELERATION_CUTOFF = 100.0;
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

        if (!CruiseModuleData.hasModule(vehicle)) {
            access.iacruise$setRoute(CruiseRoute.empty());
            access.iacruise$setBoosting(false);
            if (vehicle instanceof EngineVehicle engineVehicle) {
                BOOST_LEVEL.remove(engineVehicle);
            }
            TURN_MEMORY.remove(vehicle);
            ALTITUDE_MEMORY.remove(vehicle);
            return;
        }

        if (!vehicle.level().isClientSide()) {
            access.iacruise$refreshRouteFromModule();
        }
        CruiseRoute route = access.iacruise$getRoute();

        if (!route.isEnabled()) {
            setBoosting(vehicle, access, false);
            TURN_MEMORY.remove(vehicle);
            ALTITUDE_MEMORY.remove(vehicle);
            return;
        }

        if (route.isHoldingPattern()) {
            setBoosting(vehicle, access, false);
            tickHoldingPattern(vehicle, route);
            return;
        }

        if (!route.hasTarget()) {
            setBoosting(vehicle, access, false);
            TURN_MEMORY.remove(vehicle);
            ALTITUDE_MEMORY.remove(vehicle);
            return;
        }

        if (braking) {
            route.setEnabled(false);
            setBoosting(vehicle, access, false);
            TURN_MEMORY.remove(vehicle);
            ALTITUDE_MEMORY.remove(vehicle);
            CruiseModuleData.write(vehicle, route);
            syncRouteToClient(vehicle, route);
            notifyBrakeDisabled(vehicle);
            return;
        }

        CruiseRoute.Waypoint waypoint = route.getTarget();
        double dx = waypoint.x() + 0.5 - vehicle.getX();
        double dz = waypoint.z() + 0.5 - vehicle.getZ();
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        if (horizontalDistance <= WAYPOINT_RADIUS) {
            route.advance();
            CruiseModuleData.write(vehicle, route);
            syncRouteToClient(vehicle, route);
            waypoint = route.getTarget();
            if (waypoint == null) {
                setBoosting(vehicle, access, false);
                TURN_MEMORY.remove(vehicle);
                tickHoldingPattern(vehicle, route);
                return;
            }
            dx = waypoint.x() + 0.5 - vehicle.getX();
            dz = waypoint.z() + 0.5 - vehicle.getZ();
            horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        }

        int targetAltitude = route.getTargetAltitude();
        double altitudeError = targetAltitude - vehicle.getY();
        float yawError = yawError(vehicle.getYRot(), dx, dz);
        float turn = turnInput(vehicle, yawError);
        float climbInput = altitudeInput(vehicle, altitudeError);

        if (vehicle instanceof AirplaneEntity) {
            float pitchInput = -climbInput;
            vehicle.setInputs(turn, 0.0f, pitchInput);
        } else {
            vehicle.setInputs(turn, climbInput, 1.0f);
            vehicle.setYRot(vehicle.getYRot() - turn * 1.5f);
        }

        if (vehicle instanceof EngineVehicle engineVehicle && engineVehicle.getEngineTarget() < 1.0f) {
            engineVehicle.setEngineTarget(1.0f);
        }

        boolean awayFromFinal = isAwayFromFinal(vehicle, route);
        setBoosting(vehicle, access, shouldBoost(access, yawError, altitudeError, awayFromFinal));
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
        float turn = -1.0f;
        double altitudeError = route.getFinalAltitude() - vehicle.getY();
        float climbInput = altitudeInput(vehicle, altitudeError);
        if (vehicle instanceof AirplaneEntity) {
            float pitchInput = -climbInput;
            vehicle.setInputs(turn, 0.0f, pitchInput);
        } else {
            vehicle.setInputs(turn, climbInput, 1.0f);
            vehicle.setYRot(vehicle.getYRot() - turn * 1.5f);
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
        return Math.sqrt(dx * dx + dz * dz) > FINAL_ACCELERATION_CUTOFF;
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
        double predictedError = altitudeError - verticalSpeed * ALTITUDE_LOOKAHEAD_TICKS;
        float targetInput = Mth.clamp((float) (predictedError / ALTITUDE_CONTROL_RANGE), -ALTITUDE_MAX_INPUT, ALTITUDE_MAX_INPUT);
        if (Math.abs(altitudeError) <= ALTITUDE_DEAD_ZONE && Math.abs(verticalSpeed) <= ALTITUDE_RATE_DEAD_ZONE) {
            targetInput = 0.0f;
        }

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

    private static void setBoosting(VehicleEntity vehicle, CruiseVehicleAccess access, boolean boosting) {
        access.iacruise$setBoosting(boosting);
        if (vehicle instanceof EngineVehicle engineVehicle) {
            updateBoostLevel(engineVehicle, boosting);
        }
    }

    private static void updateBoostLevel(EngineVehicle vehicle, boolean boosting) {
        float current = BOOST_LEVEL.getOrDefault(vehicle, 0.0f);
        float target = boosting ? 1.0f : 0.0f;
        float step = boosting ? BOOST_RISE_PER_TICK : BOOST_FALL_PER_TICK;
        float next = current < target ? Math.min(target, current + step) : Math.max(target, current - step);
        if (next <= 0.001f) {
            BOOST_LEVEL.remove(vehicle);
        } else {
            BOOST_LEVEL.put(vehicle, next);
        }
    }

    private static float boostLevel(EngineVehicle vehicle, CruiseVehicleAccess access) {
        return BOOST_LEVEL.getOrDefault(vehicle, access.iacruise$isBoosting() ? 1.0f : 0.0f);
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

    private static void notifyBrakeDisabled(VehicleEntity vehicle) {
        if (vehicle.level().isClientSide()) {
            return;
        }
        if (vehicle.getControllingPassenger() instanceof Player player) {
            player.displayClientMessage(Component.translatable("message.immersive_aircraft_cruise.disabled_brake"), true);
        }
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
        CruiseRoute.Waypoint waypoint = access.iacruise$getRoute().getTarget();
        if (waypoint == null) {
            waypoint = access.iacruise$getRoute().getFinalTarget();
        }
        int altitude = access.iacruise$getRoute().isHoldingPattern()
                ? access.iacruise$getRoute().getFinalAltitude()
                : access.iacruise$getRoute().getTargetAltitude();
        return waypoint == null ? null : new Vec3(waypoint.x() + 0.5, altitude, waypoint.z() + 0.5);
    }
}
