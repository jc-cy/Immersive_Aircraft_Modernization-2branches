package com.g1739.immersiveaircraftcruise.network;


import com.g1739.immersiveaircraftcruise.CruiseItems;
import com.g1739.immersiveaircraftcruise.cruise.CruiseController;
import com.g1739.immersiveaircraftcruise.cruise.CruiseModuleData;
import com.g1739.immersiveaircraftcruise.cruise.CruiseRoute;
import immersive_aircraft.entity.VehicleEntity;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record UpdateCruiseHudPacket(int entityId, RouteStorageTarget target, boolean hudEnabled) implements CustomPacketPayload {
    public static final Type<UpdateCruiseHudPacket> TYPE = CruiseNetwork.type("update_cruise_hud");
    public static final StreamCodec<RegistryFriendlyByteBuf, UpdateCruiseHudPacket> STREAM_CODEC = StreamCodec.ofMember(UpdateCruiseHudPacket::encode, UpdateCruiseHudPacket::decode);

    public void encode(RegistryFriendlyByteBuf buffer) {
        buffer.writeInt(entityId);
        target.write(buffer);
        buffer.writeBoolean(hudEnabled);
    }

    public static UpdateCruiseHudPacket decode(RegistryFriendlyByteBuf buffer) {
        return new UpdateCruiseHudPacket(buffer.readInt(), RouteStorageTarget.read(buffer), buffer.readBoolean());
    }

    @Override
    public Type<UpdateCruiseHudPacket> type() {
        return TYPE;
    }

    public static void handle(UpdateCruiseHudPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
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
            if (entity instanceof VehicleEntity vehicle && CruiseController.isPilot(vehicle, player)) {
                if (!CruiseController.hasCruiseModule(vehicle)) {
                    return;
                }
                CruiseRoute route = CruiseController.currentRoute(vehicle).copy();
                route.setHudEnabled(packet.hudEnabled);
                CruiseModuleData.write(vehicle, route);
                if (vehicle instanceof com.g1739.immersiveaircraftcruise.cruise.CruiseVehicleAccess access) {
                    access.iacruise$setRoute(route.copy());
                }
                CruiseController.syncRouteToPassengers(vehicle, route);
            }
        });
    }
}
