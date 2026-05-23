package com.g1739.immersiveaircraftcruise.cruise;

import com.g1739.immersiveaircraftcruise.CruiseItems;
import immersive_aircraft.entity.InventoryVehicleEntity;
import immersive_aircraft.entity.VehicleEntity;
import immersive_aircraft.entity.inventory.VehicleInventoryDescription;
import immersive_aircraft.entity.inventory.slots.SlotDescription;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

public final class CruiseModuleData {
    public static final String ROUTE_TAG = "ImmersiveAircraftCruiseRoute";
    public static final String BOOSTING_TAG = "ImmersiveAircraftCruiseBoosting";

    private CruiseModuleData() {
    }

    public static Optional<ItemStack> findModule(VehicleEntity vehicle) {
        if (!(vehicle instanceof InventoryVehicleEntity inventoryVehicle)) {
            return Optional.empty();
        }
        for (SlotDescription slot : inventoryVehicle.getInventoryDescription().getSlots(VehicleInventoryDescription.UPGRADE)) {
            ItemStack stack = inventoryVehicle.getInventory().getItem(slot.index());
            if (stack.is(CruiseItems.CRUISE_MODULE.get())) {
                return Optional.of(stack);
            }
        }
        return Optional.empty();
    }

    public static boolean hasModule(VehicleEntity vehicle) {
        return findModule(vehicle).isPresent();
    }

    public static CruiseRoute read(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(ROUTE_TAG, Tag.TAG_COMPOUND)) {
            return CruiseRoute.empty();
        }
        return CruiseRoute.fromTag(tag.getCompound(ROUTE_TAG));
    }

    public static CruiseRoute read(VehicleEntity vehicle) {
        return findModule(vehicle).map(CruiseModuleData::read).orElseGet(CruiseRoute::empty);
    }

    public static void write(ItemStack stack, CruiseRoute route) {
        stack.getOrCreateTag().put(ROUTE_TAG, (route == null ? CruiseRoute.empty() : route).toTag());
    }

    public static boolean write(VehicleEntity vehicle, CruiseRoute route) {
        if (!(vehicle instanceof InventoryVehicleEntity inventoryVehicle)) {
            return false;
        }
        for (SlotDescription slot : inventoryVehicle.getInventoryDescription().getSlots(VehicleInventoryDescription.UPGRADE)) {
            ItemStack stack = inventoryVehicle.getInventory().getItem(slot.index());
            if (stack.is(CruiseItems.CRUISE_MODULE.get())) {
                write(stack, route);
                inventoryVehicle.getInventory().setItem(slot.index(), stack);
                return true;
            }
        }
        return false;
    }

    public static void setBoosting(VehicleEntity vehicle, boolean boosting) {
        if (!(vehicle instanceof InventoryVehicleEntity inventoryVehicle)) {
            return;
        }
        for (SlotDescription slot : inventoryVehicle.getInventoryDescription().getSlots(VehicleInventoryDescription.UPGRADE)) {
            ItemStack stack = inventoryVehicle.getInventory().getItem(slot.index());
            if (!stack.is(CruiseItems.CRUISE_MODULE.get())) {
                continue;
            }
            boolean changed = false;
            if (boosting) {
                if (!stack.getOrCreateTag().getBoolean(BOOSTING_TAG)) {
                    stack.getOrCreateTag().putBoolean(BOOSTING_TAG, true);
                    changed = true;
                }
            } else if (stack.hasTag() && stack.getTag().contains(BOOSTING_TAG)) {
                stack.getTag().remove(BOOSTING_TAG);
                changed = true;
            }
            if (changed) {
                inventoryVehicle.getInventory().setItem(slot.index(), stack);
            }
            return;
        }
    }
}
