package com.g1739.immersiveaircraftcruise.network;

import com.g1739.immersiveaircraftcruise.cruise.CruiseController;
import com.g1739.immersiveaircraftcruise.cruise.CruiseRoute;
import immersive_aircraft.entity.VehicleEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

public class RequestOpenCruiseScreenPacket {
    private final int entityId;
    private final int selectedRoute;
    private final int currentIndex;
    private final boolean holdingPattern;
    private final boolean initialAltitudeReached;
    private final CruiseRoute.Waypoint startPoint;

    public RequestOpenCruiseScreenPacket() {
        this(-1, null);
    }

    public RequestOpenCruiseScreenPacket(int entityId, CruiseRoute route) {
        this.entityId = entityId;
        this.selectedRoute = route == null ? -1 : route.getSelectedRoute();
        this.currentIndex = route == null ? -1 : route.getCurrentIndex();
        this.holdingPattern = route != null && route.isHoldingPattern();
        this.initialAltitudeReached = route != null && route.isInitialAltitudeReached();
        this.startPoint = route == null ? null : route.getStartPoint();
    }

    private RequestOpenCruiseScreenPacket(int entityId, int selectedRoute, int currentIndex, boolean holdingPattern,
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

    public static RequestOpenCruiseScreenPacket decode(FriendlyByteBuf buffer) {
        int entityId = buffer.readInt();
        int selectedRoute = buffer.readInt();
        int currentIndex = buffer.readInt();
        boolean holdingPattern = buffer.readBoolean();
        boolean initialAltitudeReached = buffer.readBoolean();
        CruiseRoute.Waypoint startPoint = buffer.readBoolean() ? CruiseRoute.Waypoint.read(buffer) : null;
        return new RequestOpenCruiseScreenPacket(entityId, selectedRoute, currentIndex, holdingPattern, initialAltitudeReached, startPoint);
    }

    public static void handle(RequestOpenCruiseScreenPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            Entity root = player.getRootVehicle();
            if (root instanceof VehicleEntity vehicle && CruiseController.isPilot(vehicle, player)) {
                if (!CruiseController.hasCruiseModule(vehicle)) {
                    player.displayClientMessage(Component.translatable("message.immersive_aircraft_cruise.requires_module"), true);
                    return;
                }
                CruiseRoute route = CruiseController.routeForOpeningScreen(vehicle, packet.selectedRoute,
                        packet.currentIndex, packet.holdingPattern, packet.initialAltitudeReached, packet.startPoint).copy();
                CruiseNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                        new OpenCruiseScreenPacket(vehicle.getId(), route.copy()));
            }
        });
        context.setPacketHandled(true);
    }
}
