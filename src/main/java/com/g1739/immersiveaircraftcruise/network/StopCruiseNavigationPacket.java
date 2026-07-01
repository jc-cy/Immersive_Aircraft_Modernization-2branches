package com.g1739.immersiveaircraftcruise.network;

import com.g1739.immersiveaircraftcruise.cruise.CruiseController;
import com.g1739.immersiveaircraftcruise.cruise.CruiseNavigationStopReason;
import com.g1739.immersiveaircraftcruise.cruise.CruiseRoute;
import immersive_aircraft.entity.VehicleEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class StopCruiseNavigationPacket {
    private final int entityId;
    private final int selectedRoute;
    private final int currentIndex;
    private final boolean holdingPattern;
    private final boolean initialAltitudeReached;
    private final CruiseRoute.Waypoint startPoint;
    private final CruiseNavigationStopReason stopReason;

    public StopCruiseNavigationPacket(int entityId, CruiseRoute route) {
        this(entityId, route, CruiseNavigationStopReason.NORMAL);
    }

    public StopCruiseNavigationPacket(int entityId, CruiseRoute route, CruiseNavigationStopReason stopReason) {
        this(
                entityId,
                route == null ? -1 : route.getSelectedRoute(),
                route == null ? -1 : route.getCurrentIndex(),
                route != null && route.isHoldingPattern(),
                route != null && route.isInitialAltitudeReached(),
                route == null ? null : route.getStartPoint(),
                stopReason
        );
    }

    private StopCruiseNavigationPacket(int entityId, int selectedRoute, int currentIndex, boolean holdingPattern,
                                       boolean initialAltitudeReached, CruiseRoute.Waypoint startPoint,
                                       CruiseNavigationStopReason stopReason) {
        this.entityId = entityId;
        this.selectedRoute = selectedRoute;
        this.currentIndex = currentIndex;
        this.holdingPattern = holdingPattern;
        this.initialAltitudeReached = initialAltitudeReached;
        this.startPoint = startPoint;
        this.stopReason = stopReason == null ? CruiseNavigationStopReason.NORMAL : stopReason;
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeInt(entityId);
        buffer.writeInt(selectedRoute);
        buffer.writeInt(currentIndex);
        buffer.writeBoolean(holdingPattern);
        buffer.writeBoolean(initialAltitudeReached);
        buffer.writeBoolean(startPoint != null);
        if (startPoint != null) {
            startPoint.write(buffer);
        }
        buffer.writeEnum(stopReason);
    }

    public static StopCruiseNavigationPacket decode(FriendlyByteBuf buffer) {
        int entityId = buffer.readInt();
        int selectedRoute = buffer.readInt();
        int currentIndex = buffer.readInt();
        boolean holdingPattern = buffer.readBoolean();
        boolean initialAltitudeReached = buffer.readBoolean();
        CruiseRoute.Waypoint startPoint = buffer.readBoolean() ? CruiseRoute.Waypoint.read(buffer) : null;
        CruiseNavigationStopReason stopReason = buffer.readEnum(CruiseNavigationStopReason.class);
        return new StopCruiseNavigationPacket(entityId, selectedRoute, currentIndex, holdingPattern,
                initialAltitudeReached, startPoint, stopReason);
    }

    public static void handle(StopCruiseNavigationPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            Entity root = player.getRootVehicle();
            if (!(root instanceof VehicleEntity vehicle) || !CruiseController.isPilot(vehicle, player)) {
                return;
            }
            if (!CruiseController.hasCruiseModule(vehicle)) {
                return;
            }

            CruiseRoute route = CruiseController.currentRoute(vehicle).copy();
            if (!route.isEnabled() || route.getSelectedEntry().waypoints().isEmpty()) {
                return;
            }
            packet.mergeClientProgress(vehicle, route);
            CruiseController.stopNavigation(vehicle, route, player, packet.stopReason);
        });
        context.setPacketHandled(true);
    }

    private void mergeClientProgress(VehicleEntity vehicle, CruiseRoute route) {
        if (entityId != vehicle.getId() || selectedRoute != route.getSelectedRoute()) {
            return;
        }
        int waypointCount = route.getSelectedEntry().waypoints().size();
        if (currentIndex < 0 || currentIndex >= waypointCount) {
            return;
        }
        if (currentIndex > route.getCurrentIndex()) {
            route.setCurrentIndex(currentIndex);
            route.setHoldingPattern(false);
        }
        if (initialAltitudeReached) {
            route.setInitialAltitudeReached(true);
        }
        if (holdingPattern && currentIndex == waypointCount - 1) {
            route.setCurrentIndex(currentIndex);
            route.setHoldingPattern(true);
        }
        if (!route.hasStartPoint() && startPoint != null) {
            route.setStartPoint(startPoint);
        }
    }
}
