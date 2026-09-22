package com.g1739.immersiveaircraftcruise.network;


import com.g1739.immersiveaircraftcruise.client.ClientPacketHandlers;
import immersive_aircraft.entity.InventoryVehicleEntity;
import immersive_aircraft.entity.inventory.VehicleInventoryDescription;
import immersive_aircraft.entity.inventory.slots.SlotDescription;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

public record SyncVehicleInventoryPacket(int entityId, List<Entry> entries) implements CustomPacketPayload {
    public static final Type<SyncVehicleInventoryPacket> TYPE = CruiseNetwork.type("sync_vehicle_inventory");
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncVehicleInventoryPacket> STREAM_CODEC = StreamCodec.ofMember(SyncVehicleInventoryPacket::encode, SyncVehicleInventoryPacket::decode);

    public static SyncVehicleInventoryPacket fromVehicle(InventoryVehicleEntity vehicle) {
        List<Entry> entries = new ArrayList<>();
        for (SlotDescription slot : vehicle.getInventoryDescription().getSlots()) {
            if (VehicleInventoryDescription.INVENTORY.equals(slot.type())) {
                continue;
            }
            entries.add(new Entry(slot.index(), vehicle.getInventory().getItem(slot.index())));
        }
        return new SyncVehicleInventoryPacket(vehicle.getId(), entries);
    }

    public void encode(RegistryFriendlyByteBuf buffer) {
        buffer.writeInt(entityId);
        buffer.writeInt(entries.size());
        for (Entry entry : entries) {
            buffer.writeInt(entry.slot());
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, entry.stack());
        }
    }

    public static SyncVehicleInventoryPacket decode(RegistryFriendlyByteBuf buffer) {
        int entityId = buffer.readInt();
        int size = buffer.readInt();
        List<Entry> entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            entries.add(new Entry(buffer.readInt(), ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer)));
        }
        return new SyncVehicleInventoryPacket(entityId, entries);
    }

    @Override
    public Type<SyncVehicleInventoryPacket> type() {
        return TYPE;
    }

    public static void handle(SyncVehicleInventoryPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> ClientPacketHandlers.updateVehicleInventory(packet.entityId, packet.entries));
    }

    public record Entry(int slot, ItemStack stack) {
        public Entry {
            stack = stack.copy();
        }
    }
}
