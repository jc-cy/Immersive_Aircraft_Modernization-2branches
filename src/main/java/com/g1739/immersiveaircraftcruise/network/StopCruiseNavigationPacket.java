package com.g1739.immersiveaircraftcruise.network;

import com.g1739.immersiveaircraftcruise.cruise.CruiseController;
import com.g1739.immersiveaircraftcruise.cruise.CruiseModuleData;
import com.g1739.immersiveaircraftcruise.cruise.CruiseRoute;
import immersive_aircraft.entity.VehicleEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

public class StopCruiseNavigationPacket {
    private final int entityId;
    private final int selectedRoute;
    private final int currentIndex;
    private final boolean holdingPattern;
    private final boolean initialAltitudeReached;
    private final CruiseRoute.Waypoint startPoint;

    public StopCruiseNavigationPacket(int entityId, CruiseRoute route) {
        this(
                entityId,
                route == null ? -1 : route.getSelectedRoute(),
                route == null ? -1 : route.getCurrentIndex(),
                route != null && route.isHoldingPattern(),
                route != null && route.isInitialAltitudeReached(),
                route == null ? null : route.getStartPoint()
        );
    }

    private StopCruiseNavigationPacket(int entityId, int selectedRoute, int currentIndex, boolean holdingPattern,
                                       boolean initialAltitudeReached, CruiseRoute.Waypoint startPoint) {
        this.entityId = entityId;
        this.selectedRoute = selectedRoute;
        this.currentIndex = currentIndex;
        this.holdingPattern = holdingPattern;
        this.initialAltitudeReached = initialAltitudeReached;
        this.startPoint = startPoint;
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
    }

    public static StopCruiseNavigationPacket decode(FriendlyByteBuf buffer) {
        int entityId = buffer.readInt();
        int selectedRoute = buffer.readInt();
        int currentIndex = buffer.readInt();
        boolean holdingPattern = buffer.readBoolean();
        boolean initialAltitudeReached = buffer.readBoolean();
        CruiseRoute.Waypoint startPoint = buffer.readBoolean() ? CruiseRoute.Waypoint.read(buffer) : null;
        return new StopCruiseNavigationPacket(entityId, selectedRoute, currentIndex, holdingPattern, initialAltitudeReached, startPoint);
    }

    public static void handle(StopCruiseNavigationPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            Entity root = player.getRootVehicle();
            if (!(root instanceof VehicleEntity vehicle) || !vehicle.hasPassenger(player)) {
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
            route.stopNavigation();
            CruiseController.stopNavigationEffects(vehicle);

            CruiseModuleData.write(vehicle, route);
            if (vehicle instanceof com.g1739.immersiveaircraftcruise.cruise.CruiseVehicleAccess access) {
                access.iacruise$setRoute(route.copy());
            }
            CruiseNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new UpdateCruiseRoutePacket(vehicle.getId(), route.copy()));
            player.displayClientMessage(Component.translatable("message.immersive_aircraft_cruise.disabled"), true);
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
