package com.g1739.immersiveaircraftcruise.network;

import com.g1739.immersiveaircraftcruise.CruiseItems;
import com.g1739.immersiveaircraftcruise.cruise.CruiseController;
import com.g1739.immersiveaircraftcruise.cruise.CruiseModuleData;
import com.g1739.immersiveaircraftcruise.cruise.CruiseRoute;
import com.g1739.immersiveaircraftcruise.cruise.CruiseVehicleAccess;
import immersive_aircraft.entity.VehicleEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record UpdateCruiseHudPacket(int entityId, RouteStorageTarget target, boolean hudEnabled) {
    public void encode(FriendlyByteBuf buffer) {
        buffer.writeInt(entityId);
        target.write(buffer);
        buffer.writeBoolean(hudEnabled);
    }

    public static UpdateCruiseHudPacket decode(FriendlyByteBuf buffer) {
        return new UpdateCruiseHudPacket(buffer.readInt(), RouteStorageTarget.read(buffer), buffer.readBoolean());
    }

    public static void handle(UpdateCruiseHudPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }

            if (packet.target == RouteStorageTarget.HELD_MODULE) {
                ItemStack stack = player.getMainHandItem().is(CruiseItems.CRUISE_MODULE.get()) ? player.getMainHandItem() : player.getOffhandItem();
                if (!stack.is(CruiseItems.CRUISE_MODULE.get())) {
                    return;
                }
                CruiseRoute route = CruiseModuleData.read(stack);
                route.setHudEnabled(packet.hudEnabled);
                CruiseModuleData.write(stack, route);
                return;
            }

            Entity entity = player.level().getEntity(packet.entityId);
            if (entity instanceof VehicleEntity vehicle && vehicle.hasPassenger(player) && vehicle instanceof CruiseVehicleAccess access) {
                if (!CruiseController.hasCruiseModule(vehicle)) {
                    return;
                }
                CruiseRoute route = CruiseModuleData.read(vehicle);
                route.setHudEnabled(packet.hudEnabled);
                CruiseModuleData.write(vehicle, route);
                access.iacruise$setRoute(route.copy());
            }
        });
        context.setPacketHandled(true);
    }
}
