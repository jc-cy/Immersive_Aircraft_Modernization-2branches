package com.g1739.immersiveaircraftcruise.client;


import com.g1739.immersiveaircraftcruise.cruise.CruiseRoute;
import com.g1739.immersiveaircraftcruise.cruise.CruiseVehicleAccess;
import immersive_aircraft.entity.VehicleEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

/** Identifies the forward route corridor for optional renderer integrations. */
public final class CruiseRenderPriority {
    private static final int LOOKAHEAD_CHUNKS = 32;
    private static final double THREE_WIDE_LATERAL_LIMIT = 1.75d;
    private static final double SINGLE_WIDE_LATERAL_LIMIT = 0.75d;

    private CruiseRenderPriority() {
    }

    public static boolean isForwardRouteSection(int chunkX, int chunkZ) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return false;
        }
        Entity root = minecraft.player.getRootVehicle();
        if (!(root instanceof VehicleEntity vehicle)
                || !(vehicle instanceof CruiseVehicleAccess access)) {
            return false;
        }
        CruiseRoute route = access.iacruise$getRoute();
        if (route == null || !route.isEnabled() || route.isHoldingPattern() || !route.hasTarget()) {
            return false;
        }
        if (route.getSelectedEntry().loadingMode() == CruiseRoute.RouteLoadingMode.VANILLA) {
            return false;
        }

        CruiseRoute.Waypoint target = route.getNavigationTarget(vehicle.getX(), vehicle.getZ());
        if (target == null) {
            return false;
        }
        double directionX = target.x() + 0.5d - vehicle.getX();
        double directionZ = target.z() + 0.5d - vehicle.getZ();
        double length = Math.hypot(directionX, directionZ);
        if (length < 1.0d) {
            return chunkX == vehicle.chunkPosition().x && chunkZ == vehicle.chunkPosition().z;
        }
        directionX /= length;
        directionZ /= length;

        int vehicleChunkX = vehicle.chunkPosition().x;
        int vehicleChunkZ = vehicle.chunkPosition().z;
        double relativeX = chunkX - vehicleChunkX;
        double relativeZ = chunkZ - vehicleChunkZ;
        double forwardDistance = relativeX * directionX + relativeZ * directionZ;
        if (forwardDistance < -1.0d || forwardDistance > LOOKAHEAD_CHUNKS) {
            return false;
        }
        double lateralDistance = Math.abs(relativeX * directionZ - relativeZ * directionX);
        double lateralLimit = route.isLShapedSingleMode()
                ? SINGLE_WIDE_LATERAL_LIMIT
                : THREE_WIDE_LATERAL_LIMIT;
        return lateralDistance <= lateralLimit;
    }
}
