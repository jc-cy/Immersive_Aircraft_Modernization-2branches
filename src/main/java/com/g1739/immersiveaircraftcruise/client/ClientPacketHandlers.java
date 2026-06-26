package com.g1739.immersiveaircraftcruise.client;

import com.g1739.immersiveaircraftcruise.client.gui.CruiseScreen;
import com.g1739.immersiveaircraftcruise.cruise.CruiseController;
import com.g1739.immersiveaircraftcruise.cruise.CruiseFuelInfo;
import com.g1739.immersiveaircraftcruise.cruise.CruiseModuleData;
import com.g1739.immersiveaircraftcruise.cruise.CruiseRoute;
import com.g1739.immersiveaircraftcruise.cruise.CruiseVehicleAccess;
import com.g1739.immersiveaircraftcruise.network.SyncVehicleInventoryPacket;
import immersive_aircraft.entity.InventoryVehicleEntity;
import immersive_aircraft.entity.VehicleEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

import java.util.List;

public final class ClientPacketHandlers {
    private ClientPacketHandlers() {
    }

    public static void openCruiseScreen(int entityId, CruiseRoute route, boolean readOnly) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        Entity entity = minecraft.level.getEntity(entityId);
        if (entityId < 0 || (entity instanceof VehicleEntity && entity instanceof CruiseVehicleAccess)) {
            minecraft.setScreen(new CruiseScreen(entityId, route.copy(), readOnly));
        }
    }

    public static void updateCruiseRoute(int entityId, CruiseRoute route) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        Entity entity = minecraft.level.getEntity(entityId);
        if (entity instanceof VehicleEntity && entity instanceof CruiseVehicleAccess access) {
            CruiseRoute previous = access.iacruise$getRoute();
            CruiseRoute synced = route.copy();
            access.iacruise$setRoute(synced);
            if (!synced.isEnabled() && previous != null && previous.isEnabled()) {
                CruiseController.stopNavigationEffects((VehicleEntity) entity);
                CruiseController.clearCruiseInputs((VehicleEntity) entity);
            }
        }
        if (minecraft.screen instanceof CruiseScreen screen && screen.isForEntity(entityId)) {
            screen.updateRuntime(route);
        }
    }

    public static void updateCruiseFuel(int entityId, CruiseFuelInfo fuelInfo) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null) {
            Entity entity = minecraft.level.getEntity(entityId);
            if (entity instanceof VehicleEntity vehicle) {
                CruiseModuleData.setBoosting(vehicle, isBoosting(vehicle, fuelInfo));
            }
        }
        CruiseHud.setFuelInfo(entityId, fuelInfo);
    }

    private static boolean isBoosting(VehicleEntity vehicle, CruiseFuelInfo fuelInfo) {
        return fuelInfo.boosting()
                || isLocalPilot(vehicle) && vehicle instanceof CruiseVehicleAccess access && access.iacruise$isBoosting();
    }

    private static boolean isLocalPilot(VehicleEntity vehicle) {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.player != null && vehicle.getControllingPassenger() == minecraft.player;
    }

    public static void updateVehicleInventory(int entityId, List<SyncVehicleInventoryPacket.Entry> entries) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        Entity entity = minecraft.level.getEntity(entityId);
        if (!(entity instanceof InventoryVehicleEntity vehicle)) {
            return;
        }
        int size = vehicle.getInventory().getContainerSize();
        for (SyncVehicleInventoryPacket.Entry entry : entries) {
            if (entry.slot() >= 0 && entry.slot() < size) {
                vehicle.getInventory().setItem(entry.slot(), entry.stack().copy());
            }
        }
    }
}
