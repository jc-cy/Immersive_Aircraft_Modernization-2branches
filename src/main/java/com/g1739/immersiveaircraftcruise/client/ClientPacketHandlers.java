package com.g1739.immersiveaircraftcruise.client;

import com.g1739.immersiveaircraftcruise.client.gui.CruiseScreen;
import com.g1739.immersiveaircraftcruise.cruise.CruiseFuelInfo;
import com.g1739.immersiveaircraftcruise.cruise.CruiseRoute;
import com.g1739.immersiveaircraftcruise.cruise.CruiseVehicleAccess;
import com.g1739.immersiveaircraftcruise.network.RouteStorageTarget;
import immersive_aircraft.entity.VehicleEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

public final class ClientPacketHandlers {
    private ClientPacketHandlers() {
    }

    public static void openCruiseScreen(int entityId, RouteStorageTarget target, CruiseRoute route) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        if (target == RouteStorageTarget.HELD_MODULE) {
            minecraft.setScreen(new CruiseScreen(entityId, target, route.copy()));
            return;
        }
        Entity entity = minecraft.level.getEntity(entityId);
        if (entity instanceof VehicleEntity && entity instanceof CruiseVehicleAccess) {
            minecraft.setScreen(new CruiseScreen(entityId, target, route.copy()));
        }
    }

    public static void updateCruiseRoute(int entityId, CruiseRoute route) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        Entity entity = minecraft.level.getEntity(entityId);
        if (entity instanceof VehicleEntity && entity instanceof CruiseVehicleAccess access) {
            access.iacruise$setRoute(route.copy());
        }
    }

    public static void updateCruiseFuel(int entityId, CruiseFuelInfo fuelInfo) {
        CruiseHud.setFuelInfo(entityId, fuelInfo);
    }
}
