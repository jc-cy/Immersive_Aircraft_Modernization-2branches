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
import net.minecraftforge.client.gui.overlay.ForgeGui;

import java.util.HashMap;
import java.util.Map;

public final class CruiseHud {
    private static final Map<Integer, CruiseFuelInfo> FUEL_INFO = new HashMap<>();

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

        renderHud(graphics, minecraft.font, vehicle, access, route, screenWidth, screenHeight);
    }

    public static void renderHud(GuiGraphics graphics, Font font, VehicleEntity vehicle, CruiseVehicleAccess access,
                                 CruiseRoute route, int screenWidth, int screenHeight) {
        renderHudAt(graphics, font, vehicle, access, route, x(screenWidth), y(screenHeight));
    }

    public static void renderHudAt(GuiGraphics graphics, Font font, VehicleEntity vehicle, CruiseVehicleAccess access,
                                   CruiseRoute route, int x, int y) {
        renderHudAt(graphics, font, vehicle, access.iacruise$isBoosting(), route, x, y);
    }

    public static void renderHudAt(GuiGraphics graphics, Font font, VehicleEntity vehicle, boolean boosting,
                                   CruiseRoute route, int x, int y) {
        ItemStack moduleIcon = new ItemStack(CruiseItems.CRUISE_MODULE.get());
        if (boosting) {
            moduleIcon.getOrCreateTag().putBoolean(CruiseModuleData.BOOSTING_TAG, true);
        }
        graphics.renderItem(moduleIcon, x, y);

        int textX = x + 20;
        double speed = vehicle.getDeltaMovement().length() * 20.0d;
        CruiseFuelInfo fuelInfo = FUEL_INFO.getOrDefault(vehicle.getId(), CruiseFuelInfo.EMPTY);
        ItemStack fuelIcon = fuelIcon(vehicle, fuelInfo);
        if (!fuelIcon.isEmpty()) {
            graphics.renderItem(fuelIcon, x, y + 20);
        }
        graphics.drawString(font, "速度 " + format(speed) + " 格/秒", textX, y + 2, 0xFFFFFF, true);
        graphics.drawString(font, "燃料剩余 " + fuelInfo.amountText(), textX, y + 22, 0xFFFFFF, true);
        graphics.drawString(font, "剩余时间 " + formatTime(fuelInfo.remainingTicks()), x, y + 42, 0xFFFFFF, true);
        int detailsY = y + 54;
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
        FUEL_INFO.put(entityId, fuelInfo);
    }
}
