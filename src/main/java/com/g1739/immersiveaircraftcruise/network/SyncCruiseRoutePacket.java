package com.g1739.immersiveaircraftcruise.network;


import com.g1739.immersiveaircraftcruise.cruise.CruiseController;
import com.g1739.immersiveaircraftcruise.cruise.CruiseModuleData;
import com.g1739.immersiveaircraftcruise.cruise.CruiseRoute;
import com.g1739.immersiveaircraftcruise.CruiseItems;
import immersive_aircraft.entity.VehicleEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record SyncCruiseRoutePacket(int entityId, RouteStorageTarget target, CruiseRoute route, boolean showMessage) implements CustomPacketPayload {
    public static final Type<SyncCruiseRoutePacket> TYPE = CruiseNetwork.type("sync_cruise_route");
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncCruiseRoutePacket> STREAM_CODEC = StreamCodec.ofMember(SyncCruiseRoutePacket::encode, SyncCruiseRoutePacket::decode);

    public SyncCruiseRoutePacket(int entityId, CruiseRoute route) {
        this(entityId, RouteStorageTarget.VEHICLE_MODULE, route, true);
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeInt(entityId);
        target.write(buffer);
        route.write(buffer);
        buffer.writeBoolean(showMessage);
    }

    public static SyncCruiseRoutePacket decode(FriendlyByteBuf buffer) {
        return new SyncCruiseRoutePacket(buffer.readInt(), RouteStorageTarget.read(buffer), CruiseRoute.read(buffer), buffer.readBoolean());
    }

    @Override
    public Type<SyncCruiseRoutePacket> type() {
        return TYPE;
    }

    public static void handle(SyncCruiseRoutePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            CruiseRoute route = packet.route.copy();

            if (packet.target == RouteStorageTarget.HELD_MODULE) {
                ItemStack stack = player.getMainHandItem().is(CruiseItems.CRUISE_MODULE.get()) ? player.getMainHandItem() : player.getOffhandItem();
                if (!stack.is(CruiseItems.CRUISE_MODULE.get())) {
                    player.displayClientMessage(Component.translatable("message.immersive_aircraft_cruise.requires_module"), true);
                    return;
                }
                CruiseModuleData.write(stack, route);
                sendSavedMessage(packet, player);
                return;
            }

            Entity entity = player.level().getEntity(packet.entityId);
            if (entity instanceof VehicleEntity vehicle && CruiseController.isPilot(vehicle, player)) {
                if (!CruiseController.hasCruiseModule(vehicle)) {
                    player.displayClientMessage(Component.translatable("message.immersive_aircraft_cruise.requires_module"), true);
                    return;
                }
                CruiseController.reconcileRouteDefinitionChange(
                        vehicle, CruiseController.currentRoute(vehicle).copy(), route);
                CruiseModuleData.write(vehicle, route);
                if (vehicle instanceof com.g1739.immersiveaircraftcruise.cruise.CruiseVehicleAccess access) {
                    access.iacruise$setRoute(route.copy());
                }
                CruiseController.syncRouteToPassengers(vehicle, route);
                sendSavedMessage(packet, player);
            }
        });
    }

    private static void sendSavedMessage(SyncCruiseRoutePacket packet, ServerPlayer player) {
        if (packet.showMessage) {
            player.displayClientMessage(Component.translatable("message.immersive_aircraft_cruise.saved_and_refreshed"), true);
        }
    }
}
