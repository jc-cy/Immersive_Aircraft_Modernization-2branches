package com.g1739.immersiveaircraftcruise.network;

import com.g1739.immersiveaircraftcruise.client.ClientPacketHandlers;
import immersive_aircraft.entity.InventoryVehicleEntity;
import immersive_aircraft.entity.inventory.VehicleInventoryDescription;
import immersive_aircraft.entity.inventory.slots.SlotDescription;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public record SyncVehicleInventoryPacket(int entityId, List<Entry> entries) {
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

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeInt(entityId);
        buffer.writeInt(entries.size());
        for (Entry entry : entries) {
            buffer.writeInt(entry.slot());
            buffer.writeItem(entry.stack());
        }
    }

    public static SyncVehicleInventoryPacket decode(FriendlyByteBuf buffer) {
        int entityId = buffer.readInt();
        int size = buffer.readInt();
        List<Entry> entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            entries.add(new Entry(buffer.readInt(), buffer.readItem()));
        }
        return new SyncVehicleInventoryPacket(entityId, entries);
    }

    public static void handle(SyncVehicleInventoryPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientPacketHandlers.updateVehicleInventory(packet.entityId, packet.entries)));
        context.setPacketHandled(true);
    }

    public record Entry(int slot, ItemStack stack) {
        public Entry {
            stack = stack.copy();
        }
    }
}
