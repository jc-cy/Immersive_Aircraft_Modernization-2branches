package com.g1739.immersiveaircraftcruise.client.gui;

import com.g1739.immersiveaircraftcruise.client.CruiseHud;
import com.g1739.immersiveaircraftcruise.cruise.CruiseRoute;
import com.g1739.immersiveaircraftcruise.cruise.CruiseVehicleAccess;
import com.g1739.immersiveaircraftcruise.network.CruiseNetwork;
import com.g1739.immersiveaircraftcruise.network.RouteStorageTarget;
import com.g1739.immersiveaircraftcruise.network.SyncCruiseRoutePacket;
import com.g1739.immersiveaircraftcruise.network.UpdateCruiseHudPacket;
import immersive_aircraft.entity.VehicleEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;

public class CruiseScreen extends Screen {
    private static final int ROW_HEIGHT = 24;

    private final int entityId;
    private final RouteStorageTarget target;
    private CruiseRoute route;
    private final List<DraftWaypoint> waypoints = new ArrayList<>();
    private String routeName;
    private int defaultAltitude;
    private int scrollOffset;
    private EditBox routeNameBox;
    private EditBox defaultAltitudeBox;
    private boolean dirty;
    private boolean confirmDeleteRoute;
    private boolean draggingHud;
    private boolean movingHud;
    private int dragOffsetX;
    private int dragOffsetY;

    public CruiseScreen(int entityId, RouteStorageTarget target, CruiseRoute route) {
        super(Component.translatable("screen.immersive_aircraft_cruise.title"));
        this.entityId = entityId;
        this.target = target;
        this.route = route.copy();
        loadSelectedRoute();
    }

    @Override
    protected void init() {
        rebuildCruiseWidgets();
    }

    private void loadSelectedRoute() {
        CruiseRoute.RouteEntry entry = route.getSelectedEntry();
        routeName = entry.name();
        defaultAltitude = entry.defaultAltitude();
        waypoints.clear();
        for (CruiseRoute.Waypoint waypoint : entry.waypoints()) {
            waypoints.add(new DraftWaypoint(
                    Integer.toString(waypoint.x()),
                    Integer.toString(waypoint.z()),
                    waypoint.altitude() == null ? "" : Integer.toString(waypoint.altitude()),
                    waypoint.name()
            ));
        }
    }

    private void rebuildCruiseWidgets() {
        clearWidgets();

        int panelWidth = Math.min(390, width - 24);
        int left = (width - panelWidth) / 2;
        int top = 28;

        addRenderableWidget(Button.builder(Component.literal("<"), button -> selectRoute(route.getSelectedRoute() - 1))
                .bounds(left, top, 22, 20).build());
        addRenderableWidget(Button.builder(Component.literal(">"), button -> selectRoute(route.getSelectedRoute() + 1))
                .bounds(left + 26, top, 22, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.immersive_aircraft_cruise.new_route"), button -> {
            updateSelectedEntry();
            route.addRoute();
            loadSelectedRoute();
            confirmDeleteRoute = false;
            markDirty();
            rebuildCruiseWidgets();
        }).bounds(left + 52, top, 50, 20).build());
        addRenderableWidget(Button.builder(deleteRouteLabel(), button -> deleteRoute(button))
                .bounds(left + 106, top, 70, 20).build());
        addRenderableWidget(Button.builder(hudMoveLabel(), button -> {
            movingHud = !movingHud;
            button.setMessage(hudMoveLabel());
        }).bounds(left + panelWidth - 180, top, 92, 20).build());
        addRenderableWidget(Button.builder(hudLabel(), button -> {
            toggleHud(button);
        }).bounds(left + panelWidth - 84, top, 84, 20).build());

        routeNameBox = new EditBox(font, left + 70, top + 28, 128, 20, Component.translatable("screen.immersive_aircraft_cruise.route_name"));
        routeNameBox.setValue(routeName);
        routeNameBox.setMaxLength(64);
        routeNameBox.setResponder(value -> {
            routeName = value;
            markDirty();
        });
        addRenderableWidget(routeNameBox);

        defaultAltitudeBox = new EditBox(font, left + 280, top + 28, 74, 20, Component.translatable("screen.immersive_aircraft_cruise.default_altitude"));
        defaultAltitudeBox.setValue(Integer.toString(defaultAltitude));
        defaultAltitudeBox.setFilter(CruiseScreen::isSignedIntegerText);
        defaultAltitudeBox.setResponder(value -> {
            updateDefaultAltitude();
            markDirty();
        });
        addRenderableWidget(defaultAltitudeBox);

        int listTop = top + 76;
        int visibleRows = visibleRows(listTop);
        scrollOffset = clamp(scrollOffset, 0, maxScrollOffset(visibleRows));
        for (int row = 0; row < visibleRows; row++) {
            int index = scrollOffset + row;
            if (index >= waypoints.size()) {
                break;
            }
            addWaypointWidgets(left, listTop + row * ROW_HEIGHT, index);
        }

        int buttonY = height - 28;
        addRenderableWidget(Button.builder(Component.translatable("screen.immersive_aircraft_cruise.add_current"), button -> addCurrentPosition())
                .bounds(left, buttonY, 86, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.immersive_aircraft_cruise.clear"), button -> {
            waypoints.clear();
            scrollOffset = 0;
            markDirty();
            rebuildCruiseWidgets();
        }).bounds(left + 92, buttonY, 58, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.immersive_aircraft_cruise.save"), button -> saveRoute(true))
                .bounds(left + panelWidth - 126, buttonY, 60, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.immersive_aircraft_cruise.cancel"), button -> onClose())
                .bounds(left + panelWidth - 62, buttonY, 62, 20).build());
    }

    private void addWaypointWidgets(int left, int y, int index) {
        DraftWaypoint waypoint = waypoints.get(index);
        EditBox xBox = editBox(left + 42, y, 48, waypoint.x);
        xBox.setResponder(value -> {
            waypoint.x = value;
            markDirty();
        });
        addRenderableWidget(xBox);

        EditBox zBox = editBox(left + 94, y, 48, waypoint.z);
        zBox.setResponder(value -> {
            waypoint.z = value;
            markDirty();
        });
        addRenderableWidget(zBox);

        EditBox altitudeBox = editBox(left + 146, y, 48, waypoint.altitude);
        altitudeBox.setResponder(value -> {
            waypoint.altitude = value;
            markDirty();
        });
        addRenderableWidget(altitudeBox);

        EditBox nameBox = new EditBox(font, left + 200, y, 84, 20, Component.empty());
        nameBox.setValue(waypoint.name);
        nameBox.setMaxLength(64);
        nameBox.setResponder(value -> {
            waypoint.name = value;
            markDirty();
        });
        addRenderableWidget(nameBox);

        addRenderableWidget(Button.builder(Component.literal("x"), button -> {
            waypoints.remove(index);
            scrollOffset = Math.min(scrollOffset, maxScrollOffset(visibleRows(104)));
            markDirty();
            rebuildCruiseWidgets();
        }).bounds(left + 288, y, 18, 20).build());

        if (index > 0) {
            addRenderableWidget(Button.builder(Component.literal("^"), button -> {
                swap(index, index - 1);
                markDirty();
                rebuildCruiseWidgets();
            }).bounds(left + 310, y, 18, 20).build());
        }

        if (index + 1 < waypoints.size()) {
            addRenderableWidget(Button.builder(Component.literal("v"), button -> {
                swap(index, index + 1);
                markDirty();
                rebuildCruiseWidgets();
            }).bounds(left + 332, y, 18, 20).build());
        }
    }

    private EditBox editBox(int x, int y, int width, String value) {
        EditBox box = new EditBox(font, x, y, width, 20, Component.empty());
        box.setFilter(CruiseScreen::isSignedIntegerText);
        box.setValue(value);
        return box;
    }

    private void selectRoute(int index) {
        if (index < 0 || index >= route.getRoutes().size()) {
            return;
        }
        updateSelectedEntry();
        route.setSelectedRoute(index);
        loadSelectedRoute();
        confirmDeleteRoute = false;
        scrollOffset = 0;
        markDirty();
        rebuildCruiseWidgets();
    }

    private void deleteRoute(Button button) {
        if (!confirmDeleteRoute) {
            confirmDeleteRoute = true;
            button.setMessage(deleteRouteLabel());
            return;
        }
        route.removeSelectedRoute();
        loadSelectedRoute();
        confirmDeleteRoute = false;
        scrollOffset = 0;
        markDirty();
        rebuildCruiseWidgets();
    }

    private void addCurrentPosition() {
        if (waypoints.size() >= CruiseRoute.MAX_WAYPOINTS || minecraft == null || minecraft.player == null) {
            return;
        }
        updateDefaultAltitude();
        BlockPos pos = minecraft.player.blockPosition();
        waypoints.add(new DraftWaypoint(Integer.toString(pos.getX()), Integer.toString(pos.getZ()), "", ""));
        scrollOffset = maxScrollOffset(visibleRows(104));
        markDirty();
        rebuildCruiseWidgets();
    }

    private void saveRoute(boolean showMessage) {
        updateSelectedEntry();
        CruiseRoute saved = route.copy();
        saved.setEnabled(false);
        saved.resetProgress();
        if (minecraft != null && minecraft.level != null) {
            Entity entity = minecraft.level.getEntity(entityId);
            if (entity instanceof CruiseVehicleAccess access) {
                access.iacruise$setRoute(saved.copy());
            }
        }
        CruiseNetwork.CHANNEL.sendToServer(new SyncCruiseRoutePacket(entityId, target, saved, showMessage));
        route = saved;
        dirty = false;
    }

    private void updateSelectedEntry() {
        List<CruiseRoute.Waypoint> routeWaypoints = new ArrayList<>();
        for (DraftWaypoint waypoint : waypoints) {
            Integer x = parseInteger(waypoint.x);
            Integer z = parseInteger(waypoint.z);
            if (x == null || z == null) {
                continue;
            }
            routeWaypoints.add(new CruiseRoute.Waypoint(x, z, parseInteger(waypoint.altitude), normalizeText(waypoint.name)));
        }
        updateDefaultAltitude();
        String name = routeName == null || routeName.isBlank() ? "Route " + (route.getSelectedRoute() + 1) : routeName.trim();
        route.setSelectedEntry(new CruiseRoute.RouteEntry(name, defaultAltitude, routeWaypoints));
    }

    private int visibleRows(int listTop) {
        return Math.max(1, (height - listTop - 36) / ROW_HEIGHT);
    }

    private int maxScrollOffset(int visibleRows) {
        return Math.max(0, waypoints.size() - visibleRows);
    }

    private void updateDefaultAltitude() {
        if (defaultAltitudeBox != null) {
            Integer parsed = parseInteger(defaultAltitudeBox.getValue());
            if (parsed != null) {
                defaultAltitude = clamp(parsed, -64, 512);
            }
        }
    }

    private Component hudLabel() {
        return Component.translatable("screen.immersive_aircraft_cruise.hud_display")
                .append(Component.literal(": " + (route.isHudEnabled() ? "ON" : "OFF")));
    }

    private Component hudMoveLabel() {
        return Component.translatable(movingHud
                ? "screen.immersive_aircraft_cruise.hud_move_done"
                : "screen.immersive_aircraft_cruise.hud_move");
    }

    private Component deleteRouteLabel() {
        return Component.translatable(confirmDeleteRoute
                ? "screen.immersive_aircraft_cruise.delete_route_confirm"
                : "screen.immersive_aircraft_cruise.delete_route");
    }

    private void markDirty() {
        dirty = true;
    }

    private void toggleHud(Button button) {
        boolean hudEnabled = !route.isHudEnabled();
        route.setHudEnabled(hudEnabled);
        button.setMessage(hudLabel());
        if (minecraft != null && minecraft.level != null) {
            Entity entity = minecraft.level.getEntity(entityId);
            if (entity instanceof VehicleEntity vehicle && entity instanceof CruiseVehicleAccess access) {
                CruiseRoute synced = access.iacruise$getRoute().copy();
                synced.setHudEnabled(hudEnabled);
                access.iacruise$setRoute(synced);
            }
            CruiseNetwork.CHANNEL.sendToServer(new UpdateCruiseHudPacket(entityId, target, hudEnabled));
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (movingHud && button == 0 && isInsideHud(mouseX, mouseY)) {
            draggingHud = true;
            dragOffsetX = (int) mouseX - CruiseHud.x(width);
            dragOffsetY = (int) mouseY - CruiseHud.y(height);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingHud && button == 0) {
            CruiseHud.setPosition((int) mouseX - dragOffsetX, (int) mouseY - dragOffsetY, width, height);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (draggingHud && button == 0) {
            draggingHud = false;
            CruiseHud.savePosition();
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int listTop = 104;
        int visibleRows = visibleRows(listTop);
        int next = clamp(scrollOffset - (int) Math.signum(delta), 0, maxScrollOffset(visibleRows));
        if (next != scrollOffset) {
            scrollOffset = next;
            rebuildCruiseWidgets();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        int panelWidth = Math.min(390, width - 24);
        int left = (width - panelWidth) / 2;
        graphics.drawString(font, title, left, 10, 0xFFFFFF, false);
        graphics.drawString(font, Component.literal((route.getSelectedRoute() + 1) + "/" + route.getRoutes().size()).withStyle(ChatFormatting.GRAY), left + 180, 34, 0xA0A0A0, false);
        graphics.drawString(font, Component.translatable("screen.immersive_aircraft_cruise.route_name"), left, 62, 0xD0D0D0, false);
        graphics.drawString(font, Component.translatable("screen.immersive_aircraft_cruise.default_altitude"), left + 204, 62, 0xD0D0D0, false);
        graphics.drawString(font, Component.literal("X"), left + 42, 92, 0xA0A0A0, false);
        graphics.drawString(font, Component.literal("Z"), left + 94, 92, 0xA0A0A0, false);
        graphics.drawString(font, Component.translatable("screen.immersive_aircraft_cruise.segment_altitude"), left + 146, 92, 0xA0A0A0, false);
        graphics.drawString(font, Component.translatable("screen.immersive_aircraft_cruise.waypoint_name"), left + 200, 92, 0xA0A0A0, false);

        int listTop = 104;
        int visibleRows = visibleRows(listTop);
        for (int row = 0; row < visibleRows; row++) {
            int index = scrollOffset + row;
            if (index >= waypoints.size()) {
                break;
            }
            int y = listTop + row * ROW_HEIGHT + 6;
            graphics.drawString(font, Component.translatable("screen.immersive_aircraft_cruise.waypoint", index + 1), left, y, 0xD8D8D8, false);
        }
        if (waypoints.size() > visibleRows) {
            graphics.drawString(font, Component.literal((scrollOffset + 1) + "-" + Math.min(waypoints.size(), scrollOffset + visibleRows) + "/" + waypoints.size()).withStyle(ChatFormatting.GRAY),
                    left + panelWidth - 54, 92, 0xA0A0A0, false);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
        renderHudPreview(graphics);
    }

    private boolean isInsideHud(double mouseX, double mouseY) {
        int x = CruiseHud.x(width);
        int y = CruiseHud.y(height);
        return mouseX >= x && mouseX < x + CruiseHud.width() && mouseY >= y && mouseY < y + CruiseHud.height();
    }

    private void renderHudPreview(GuiGraphics graphics) {
        if (!movingHud || minecraft == null || minecraft.level == null || entityId < 0) {
            return;
        }
        Entity entity = minecraft.level.getEntity(entityId);
        if (entity instanceof VehicleEntity vehicle && entity instanceof CruiseVehicleAccess access) {
            CruiseHud.renderHud(graphics, font, vehicle, access, route, width, height);
            int x = CruiseHud.x(width);
            int y = CruiseHud.y(height);
            graphics.renderOutline(x - 2, y - 2, CruiseHud.width() + 4, CruiseHud.height() + 4, 0xFFFFFFFF);
        }
    }

    private void swap(int a, int b) {
        DraftWaypoint waypoint = waypoints.get(a);
        waypoints.set(a, waypoints.get(b));
        waypoints.set(b, waypoint);
    }

    private static boolean isSignedIntegerText(String value) {
        if (value.isEmpty() || "-".equals(value)) {
            return true;
        }
        try {
            Integer.parseInt(value);
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static Integer parseInteger(String value) {
        if (value == null || value.isBlank() || "-".equals(value)) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value.strip();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class DraftWaypoint {
        private String x;
        private String z;
        private String altitude;
        private String name;

        private DraftWaypoint(String x, String z, String altitude, String name) {
            this.x = x;
            this.z = z;
            this.altitude = altitude;
            this.name = name;
        }
    }
}
