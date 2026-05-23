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
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

public class ToggleCruiseNavigationPacket {
    public void encode(FriendlyByteBuf buffer) {
    }

    public static ToggleCruiseNavigationPacket decode(FriendlyByteBuf buffer) {
        return new ToggleCruiseNavigationPacket();
    }

    public static void handle(ToggleCruiseNavigationPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            Entity root = player.getRootVehicle();
            if (root instanceof VehicleEntity vehicle && vehicle.hasPassenger(player) && vehicle instanceof CruiseVehicleAccess access) {
                if (!CruiseController.hasCruiseModule(vehicle)) {
                    player.displayClientMessage(Component.translatable("message.immersive_aircraft_cruise.requires_module"), true);
                    return;
                }
                CruiseRoute route = access.iacruise$getRoute().copy();
                if (route.getSelectedEntry().waypoints().isEmpty()) {
                    player.displayClientMessage(Component.translatable("message.immersive_aircraft_cruise.no_route"), true);
                    return;
                }
                if (route.isEnabled()) {
                    route.pause();
                    player.displayClientMessage(Component.translatable("message.immersive_aircraft_cruise.paused"), true);
                } else {
                    Vec3 position = vehicle.position();
                    boolean hadStartPoint = route.hasStartPoint();
                    route.resume(new CruiseRoute.Waypoint((int) Math.floor(position.x), (int) Math.floor(position.z), null));
                    player.displayClientMessage(Component.translatable(
                            hadStartPoint ? "message.immersive_aircraft_cruise.resumed" : "message.immersive_aircraft_cruise.enabled"), true);
                }
                CruiseModuleData.write(vehicle, route);
                access.iacruise$setRoute(route.copy());
                CruiseNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                        new UpdateCruiseRoutePacket(vehicle.getId(), route.copy()));
            }
        });
        context.setPacketHandled(true);
    }
}
