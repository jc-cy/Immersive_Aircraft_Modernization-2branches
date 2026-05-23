package com.g1739.immersiveaircraftcruise.network;

import com.g1739.immersiveaircraftcruise.cruise.CruiseController;
import com.g1739.immersiveaircraftcruise.cruise.CruiseModuleData;
import com.g1739.immersiveaircraftcruise.cruise.CruiseRoute;
import com.g1739.immersiveaircraftcruise.cruise.CruiseVehicleAccess;
import immersive_aircraft.entity.VehicleEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

public class RequestOpenCruiseScreenPacket {
    public void encode(FriendlyByteBuf buffer) {
    }

    public static RequestOpenCruiseScreenPacket decode(FriendlyByteBuf buffer) {
        return new RequestOpenCruiseScreenPacket();
    }

    public static void handle(RequestOpenCruiseScreenPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            Entity root = player.getRootVehicle();
            if (root instanceof VehicleEntity vehicle && vehicle.hasPassenger(player) && vehicle instanceof CruiseVehicleAccess access) {
                if (CruiseController.hasCruiseModule(vehicle)) {
                    CruiseRoute route = access.iacruise$getRoute().copy();
                    CruiseRoute moduleRoute = CruiseModuleData.read(vehicle);
                    if (isDefaultRoute(route) && !isDefaultRoute(moduleRoute)) {
                        route = moduleRoute;
                    }
                    CruiseModuleData.write(vehicle, route);
                    access.iacruise$setRoute(route.copy());
                    CruiseNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                            new OpenCruiseScreenPacket(vehicle.getId(), RouteStorageTarget.VEHICLE_MODULE, route.copy()));
                } else {
                    player.displayClientMessage(Component.translatable("message.immersive_aircraft_cruise.requires_module"), true);
                }
            }
        });
        context.setPacketHandled(true);
    }

    private static boolean isDefaultRoute(CruiseRoute route) {
        return route.getSelectedRoute() == 0
                && route.getCurrentIndex() == 0
                && !route.hasStartPoint()
                && route.getRoutes().size() == 1
                && route.getSelectedEntry().waypoints().isEmpty();
    }
}
