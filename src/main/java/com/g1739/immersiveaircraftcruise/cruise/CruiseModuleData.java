package com.g1739.immersiveaircraftcruise.cruise;


import com.g1739.immersiveaircraftcruise.CruiseItems;
import immersive_aircraft.entity.InventoryVehicleEntity;
import immersive_aircraft.entity.VehicleEntity;
import immersive_aircraft.entity.inventory.VehicleInventoryDescription;
import immersive_aircraft.entity.inventory.slots.SlotDescription;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;
import java.util.UUID;

public final class CruiseModuleData {
    public static final String ROUTE_TAG = "ImmersiveAircraftCruiseRoute";
    public static final String BOOSTING_TAG = "ImmersiveAircraftCruiseBoosting";
    public static final String MODULE_ID_TAG = "ImmersiveAircraftCruiseModuleId";

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

    /**
     * Preload loading modes stream the route from the server, so their aircraft has to keep ticking
     * across chunk borders the way a player does. Vanilla mode never needs that guarantee.
     */
    public static boolean usesPreloadRoute(VehicleEntity vehicle) {
        return findModule(vehicle)
                .map(CruiseModuleData::read)
                .map(route -> route.getSelectedEntry().loadingMode() != CruiseRoute.RouteLoadingMode.VANILLA)
                .orElse(false);
    }

    public static CruiseRoute read(ItemStack stack) {
        CompoundTag tag = readCustomData(stack);
        if (!tag.contains(ROUTE_TAG, Tag.TAG_COMPOUND)) {
            return CruiseRoute.empty();
        }
        return CruiseRoute.fromTag(tag.getCompound(ROUTE_TAG));
    }

    public static CruiseRoute read(VehicleEntity vehicle) {
        return findModule(vehicle).map(CruiseModuleData::read).orElseGet(CruiseRoute::empty);
    }

    public static String moduleId(ItemStack stack) {
        CompoundTag tag = readCustomData(stack);
        if (!tag.contains(MODULE_ID_TAG, Tag.TAG_STRING) || tag.getString(MODULE_ID_TAG).isBlank()) {
            tag.putString(MODULE_ID_TAG, UUID.randomUUID().toString());
            writeCustomData(stack, tag);
        }
        return tag.getString(MODULE_ID_TAG);
    }

    public static void write(ItemStack stack, CruiseRoute route) {
        CompoundTag tag = readCustomData(stack);
        if (!tag.contains(MODULE_ID_TAG, Tag.TAG_STRING) || tag.getString(MODULE_ID_TAG).isBlank()) {
            tag.putString(MODULE_ID_TAG, UUID.randomUUID().toString());
        }
        tag.put(ROUTE_TAG, (route == null ? CruiseRoute.empty() : route).toTag());
        writeCustomData(stack, tag);
    }

    public static String moduleId(VehicleEntity vehicle, ItemStack stack) {
        CompoundTag tag = readCustomData(stack);
        boolean hadModuleId = tag.contains(MODULE_ID_TAG, Tag.TAG_STRING)
                && !tag.getString(MODULE_ID_TAG).isBlank();
        String moduleId = moduleId(stack);
        if (!hadModuleId && vehicle instanceof InventoryVehicleEntity inventoryVehicle) {
            for (SlotDescription slot : inventoryVehicle.getInventoryDescription().getSlots(VehicleInventoryDescription.UPGRADE)) {
                if (inventoryVehicle.getInventory().getItem(slot.index()) == stack) {
                    inventoryVehicle.getInventory().setItem(slot.index(), stack);
                    break;
                }
            }
        }
        return moduleId;
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
                if (!isBoosting(stack)) {
                    setBoosting(stack, true);
                    changed = true;
                }
            } else if (hasCustomData(stack, BOOSTING_TAG)) {
                removeCustomData(stack, BOOSTING_TAG);
                changed = true;
            }
            if (changed) {
                inventoryVehicle.getInventory().setItem(slot.index(), stack);
            }
            return;
        }
    }

    public static boolean isBoosting(ItemStack stack) {
        return readCustomData(stack).getBoolean(BOOSTING_TAG);
    }

    public static void setBoosting(ItemStack stack, boolean boosting) {
        CompoundTag tag = readCustomData(stack);
        if (boosting) {
            tag.putBoolean(BOOSTING_TAG, true);
        } else {
            tag.remove(BOOSTING_TAG);
        }
        writeCustomData(stack, tag);
    }

    public static void clearBoosting(ItemStack stack) {
        if (hasCustomData(stack, BOOSTING_TAG)) {
            removeCustomData(stack, BOOSTING_TAG);
        }
    }

    private static boolean hasCustomData(ItemStack stack, String key) {
        return readCustomData(stack).contains(key);
    }

    private static CompoundTag readCustomData(ItemStack stack) {
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        return customData == null ? new CompoundTag() : customData.copyTag();
    }

    private static void removeCustomData(ItemStack stack, String key) {
        CompoundTag tag = readCustomData(stack);
        tag.remove(key);
        writeCustomData(stack, tag);
    }

    private static void writeCustomData(ItemStack stack, CompoundTag tag) {
        if (tag.isEmpty()) {
            stack.remove(DataComponents.CUSTOM_DATA);
        } else {
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        }
    }
}
