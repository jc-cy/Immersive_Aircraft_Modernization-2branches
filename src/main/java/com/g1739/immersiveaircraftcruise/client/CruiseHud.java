package com.g1739.immersiveaircraftcruise.client;

import com.g1739.immersiveaircraftcruise.CruiseItems;
import com.g1739.immersiveaircraftcruise.cruise.CruiseFuelInfo;
import com.g1739.immersiveaircraftcruise.cruise.CruiseModuleData;
import com.g1739.immersiveaircraftcruise.cruise.CruiseRoute;
import com.g1739.immersiveaircraftcruise.cruise.CruiseVehicleAccess;
import immersive_aircraft.entity.InventoryVehicleEntity;
import immersive_aircraft.entity.VehicleEntity;
import immersive_aircraft.entity.inventory.VehicleInventoryDescription;
import immersive_aircraft.entity.inventory.slots.SlotDescription;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.gui.overlay.ForgeGui;

import java.util.HashMap;
import java.util.Map;

public final class CruiseHud {
    private static final double LOCAL_SPEED_MAX = 512.0d;
    private static final Map<Integer, FuelSnapshot> FUEL_INFO = new HashMap<>();
    /** Flight time is sampled once per second from the client tick, never per render frame. */
    private static final Map<Integer, FlightSnapshot> FLIGHT_INFO = new HashMap<>();
    /** One local pilot sample per client tick; the HUD and heartbeat read the same value. */
    private static final Map<Integer, PositionSample> LOCAL_SPEED_SAMPLES = new HashMap<>();

    private record FuelSnapshot(CruiseFuelInfo info, long clientGameTime) {
    }

    private record FlightSnapshot(int remainingTicks) {
    }

    private CruiseHud() {
    }

    public static void render(ForgeGui gui, GuiGraphics graphics, float partialTick, int screenWidth, int screenHeight) {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null) {
            return;
        }
        Entity root = player.getRootVehicle();
        if (!(root instanceof VehicleEntity vehicle) || !(vehicle instanceof CruiseVehicleAccess access)) {
            return;
        }
        if (!CruiseModuleData.hasModule(vehicle)) {
            return;
        }
        CruiseRoute route = access.iacruise$getRoute();
        if (!route.isHudEnabled()) {
            return;
        }

        renderHud(graphics, minecraft.font, vehicle, route, screenWidth, screenHeight);
    }

    public static void renderHud(GuiGraphics graphics, Font font, VehicleEntity vehicle,
                                 CruiseRoute route, int screenWidth, int screenHeight) {
        renderHudAt(graphics, font, vehicle, route, x(screenWidth), y(screenHeight));
    }

    public static void renderHudAt(GuiGraphics graphics, Font font, VehicleEntity vehicle, CruiseRoute route, int x, int y) {
        CruiseFuelInfo fuelInfo = fuelInfoForDisplay(vehicle);
        renderHud(graphics, font, vehicle, fuelInfo.boosting(), route, fuelInfo, x, y);
    }

    public static void renderHudAt(GuiGraphics graphics, Font font, VehicleEntity vehicle, boolean boosting,
                                   CruiseRoute route, int x, int y) {
        renderHud(graphics, font, vehicle, boosting, route, fuelInfoForDisplay(vehicle), x, y);
    }

    private static void renderHud(GuiGraphics graphics, Font font, VehicleEntity vehicle, boolean boosting,
                                  CruiseRoute route, CruiseFuelInfo fuelInfo, int x, int y) {
        ItemStack moduleIcon = new ItemStack(CruiseItems.CRUISE_MODULE.get());
        if (boosting) {
            moduleIcon.getOrCreateTag().putBoolean(CruiseModuleData.BOOSTING_TAG, true);
        }
        graphics.renderItem(moduleIcon, x, y);

        int textX = x + 20;
        double speed = displaySpeed(vehicle, fuelInfo);
        int flightRemainingTicks = flightTimeForDisplay(vehicle, route);
        ItemStack fuelIcon = fuelIcon(vehicle, fuelInfo);
        if (!fuelIcon.isEmpty()) {
            graphics.renderItem(fuelIcon, x, y + 20);
        }
        graphics.drawString(font, "速度 " + format(speed) + " 格/秒", textX, y + 2, 0xFFFFFF, true);
        graphics.drawString(font, "燃料剩余 " + fuelInfo.amountText(), textX, y + 22, 0xFFFFFF, true);
        graphics.drawString(font, "燃料剩余时间 " + formatTime(fuelInfo.remainingTicks()), x, y + 42, 0xFFFFFF, true);
        graphics.drawString(font, "飞行剩余时间 " + formatTime(flightRemainingTicks), x, y + 52, 0xFFFFFF, true);
        int detailsY = y + 64;
        String routeName = route.getSelectedRouteDisplayName();
        if (!routeName.isBlank()) {
            graphics.drawString(font, fitText(font, routeName, CruiseHudSettings.WIDTH), x, detailsY, 0xFFE28A, true);
            detailsY += 10;
        }
        graphics.drawString(font, "起点 " + formatPoint(route.getStartPoint()), x, detailsY, 0xD8D8D8, true);
        graphics.drawString(font, "上站 " + formatPoint(previousPoint(route)), x, detailsY + 10, 0xD8D8D8, true);
        graphics.drawString(font, "下站 " + formatPoint(nextPoint(route)), x, detailsY + 20, 0xD8D8D8, true);
        graphics.drawString(font, "终点 " + formatPoint(route.getFinalTarget()), x, detailsY + 30, 0xD8D8D8, true);
    }

    public static int x(int screenWidth) {
        return CruiseHudSettings.x(screenWidth);
    }

    public static int y(int screenHeight) {
        return CruiseHudSettings.y(screenHeight);
    }

    public static void setPosition(int x, int y, int screenWidth, int screenHeight) {
        CruiseHudSettings.setPosition(x, y, screenWidth, screenHeight);
    }

    public static void savePosition() {
        CruiseHudSettings.save();
    }

    public static int width() {
        return CruiseHudSettings.WIDTH;
    }

    public static int height() {
        return CruiseHudSettings.HEIGHT;
    }

    private static CruiseRoute.Waypoint previousPoint(CruiseRoute route) {
        CruiseRoute.Waypoint previous = route.getPreviousWaypoint();
        return previous == null ? route.getStartPoint() : previous;
    }

    private static CruiseRoute.Waypoint nextPoint(CruiseRoute route) {
        CruiseRoute.Waypoint target = route.getTarget();
        if (target != null) {
            return target;
        }
        CruiseRoute.Waypoint current = route.getCurrentWaypoint();
        return current == null ? route.getFinalTarget() : current;
    }

    private static ItemStack fuelIcon(VehicleEntity vehicle, CruiseFuelInfo fuelInfo) {
        if (!fuelInfo.icon().isEmpty()) {
            return fuelInfo.icon();
        }
        if (vehicle instanceof InventoryVehicleEntity inventoryVehicle) {
            for (SlotDescription slot : inventoryVehicle.getInventoryDescription().getSlots(VehicleInventoryDescription.BOILER)) {
                ItemStack stack = inventoryVehicle.getInventory().getItem(slot.index());
                if (!stack.isEmpty()) {
                    return stack;
                }
            }
        }
        return ItemStack.EMPTY;
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    private static double displaySpeed(VehicleEntity vehicle, CruiseFuelInfo fuelInfo) {
        return fuelInfo.speed();
    }

    private static int flightTimeForDisplay(VehicleEntity vehicle, CruiseRoute route) {
        if (route == null || !route.isEnabled() || !route.hasTarget()) {
            return -1;
        }
        FlightSnapshot snapshot = FLIGHT_INFO.get(vehicle.getId());
        if (snapshot == null) {
            return -1;
        }
        return snapshot.remainingTicks();
    }

    private static int calculateFlightRemainingTicks(VehicleEntity vehicle, CruiseRoute route, double speed) {
        if (route == null || !route.isEnabled() || !route.hasTarget()) {
            return -1;
        }
        double distance = route.remainingHorizontalDistance(vehicle.getX(), vehicle.getZ());
        if (distance <= 0.0d) {
            return 0;
        }
        if (!Double.isFinite(speed) || speed <= 0.05d) {
            return -1;
        }
        double ticks = distance * 20.0d / speed;
        return ticks >= Integer.MAX_VALUE ? Integer.MAX_VALUE : Math.max(0, (int) Math.ceil(ticks));
    }

    private static CruiseFuelInfo fuelInfoForDisplay(VehicleEntity vehicle) {
        FuelSnapshot snapshot = FUEL_INFO.get(vehicle.getId());
        CruiseFuelInfo info = snapshot == null ? CruiseFuelInfo.EMPTY : snapshot.info();
        Minecraft minecraft = Minecraft.getInstance();
        long now = minecraft.level == null ? -1L : minecraft.level.getGameTime();
        int remainingTicks = info.remainingTicks();
        if (snapshot != null && remainingTicks >= 0 && now >= snapshot.clientGameTime()) {
            long elapsed = now - snapshot.clientGameTime();
            remainingTicks = (int) Math.max(0L, remainingTicks - elapsed);
        }

        float speed = info.speed();
        if (minecraft.player != null && vehicle.getControllingPassenger() == minecraft.player) {
            PositionSample localSample = LOCAL_SPEED_SAMPLES.get(vehicle.getId());
            if (localSample != null) {
                speed = localSample.speed();
            }
        }
        return new CruiseFuelInfo(info.amountText(), remainingTicks, info.icon(), speed, info.boosting());
    }

    public static float sampleLocalSpeed(VehicleEntity vehicle) {
        Minecraft minecraft = Minecraft.getInstance();
        long clientGameTime = minecraft.level == null ? -1L : minecraft.level.getGameTime();
        double velocitySpeed = vehicle.getDeltaMovement().length() * 20.0d;
        if (clientGameTime < 0L) {
            return (float) Math.min(LOCAL_SPEED_MAX, Math.max(0.0d, velocitySpeed));
        }
        PositionSample previous = LOCAL_SPEED_SAMPLES.get(vehicle.getId());
        if (previous != null && previous.clientGameTime() == clientGameTime) {
            return previous.speed();
        }
        Vec3 position = vehicle.position();
        double sampledSpeed = 0.0d;
        if (previous != null && clientGameTime > previous.clientGameTime()) {
            long elapsedTicks = Math.max(1L, clientGameTime - previous.clientGameTime());
            sampledSpeed = position.distanceTo(previous.position()) * 20.0d / elapsedTicks;
        }
        double speed = Math.max(velocitySpeed, sampledSpeed);
        float clampedSpeed = (float) Math.min(LOCAL_SPEED_MAX, Math.max(0.0d, speed));
        LOCAL_SPEED_SAMPLES.put(vehicle.getId(), new PositionSample(position, clientGameTime, clampedSpeed));
        return clampedSpeed;
    }

    /**
     * Refreshes the lightweight flight-time estimate once per real second. The
     * HUD render path only reads the cached value, so high render rates and
     * route length no longer add per-frame work.
     */
    public static void clientTick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        Entity root = minecraft.player.getRootVehicle();
        if (!(root instanceof VehicleEntity vehicle)
                || !(root instanceof CruiseVehicleAccess access)
                || !CruiseModuleData.hasModule(vehicle)) {
            return;
        }
        if (vehicle.getControllingPassenger() == minecraft.player) {
            sampleLocalSpeed(vehicle);
        }
        long gameTime = minecraft.level.getGameTime();
        if (gameTime % 20L != 0L) {
            return;
        }
        CruiseRoute route = access.iacruise$getRoute();
        if (!route.isEnabled() || !route.hasTarget()) {
            FLIGHT_INFO.remove(vehicle.getId());
            return;
        }
        CruiseFuelInfo displayInfo = fuelInfoForDisplay(vehicle);
        int remainingTicks = calculateFlightRemainingTicks(vehicle, route, displayInfo.speed());
        FLIGHT_INFO.put(vehicle.getId(), new FlightSnapshot(remainingTicks));
    }

    private static String formatPoint(CruiseRoute.Waypoint waypoint) {
        if (waypoint == null) {
            return "-";
        }
        String label = waypoint.displayLabel();
        String coordinates = waypoint.x() + "," + waypoint.z();
        return waypoint.hasName() ? label + " (" + coordinates + ")" : coordinates;
    }

    private static String fitText(Font font, String text, int maxWidth) {
        if (maxWidth <= 0 || font.width(text) <= maxWidth) {
            return text;
        }
        String suffix = "...";
        int end = text.length();
        while (end > 0 && font.width(text.substring(0, end) + suffix) > maxWidth) {
            end--;
        }
        return end <= 0 ? suffix : text.substring(0, end) + suffix;
    }

    private static String formatTime(int ticks) {
        if (ticks < 0) {
            return "-";
        }
        int seconds = Math.max(0, ticks / 20);
        int hours = seconds / 3600;
        int minutes = (seconds % 3600) / 60;
        int remainingSeconds = seconds % 60;
        if (hours > 0) {
            return String.format(java.util.Locale.ROOT, "%d:%02d:%02d", hours, minutes, remainingSeconds);
        }
        return String.format(java.util.Locale.ROOT, "%02d:%02d", minutes, remainingSeconds);
    }

    public static void setFuelInfo(int entityId, CruiseFuelInfo fuelInfo) {
        Minecraft minecraft = Minecraft.getInstance();
        long clientGameTime = minecraft.level == null ? -1L : minecraft.level.getGameTime();
        CruiseFuelInfo syncedInfo = fuelInfo == null ? CruiseFuelInfo.EMPTY : fuelInfo;
        FUEL_INFO.put(entityId, new FuelSnapshot(
                syncedInfo,
                clientGameTime));
    }

    public static void clearFuelInfo() {
        FUEL_INFO.clear();
        FLIGHT_INFO.clear();
        LOCAL_SPEED_SAMPLES.clear();
    }

    public static void invalidateSpeedSample(int entityId) {
        LOCAL_SPEED_SAMPLES.remove(entityId);
    }

    public static void invalidateFlightTime(int entityId) {
        FLIGHT_INFO.remove(entityId);
    }

    private record PositionSample(Vec3 position, long clientGameTime, float speed) {
    }

}
