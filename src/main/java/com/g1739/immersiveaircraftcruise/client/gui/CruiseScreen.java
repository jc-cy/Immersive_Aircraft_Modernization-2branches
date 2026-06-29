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
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;

public class CruiseScreen extends Screen {
    private static final int ROW_HEIGHT = 24;
    private static final int PANEL_WIDTH = 470;
    // Main route editor layout knobs for quick position/size tuning.
    private static final int TOP_ROW_Y = 28;
    private static final int ROUTE_SETTINGS_ROW_Y = TOP_ROW_Y + 28;
    private static final int LABEL_CONTENT_GAP = 14;
    private static final int CONTROL_GAP = 16;
    private static final int BUTTON_HORIZONTAL_PADDING = 20;
    private static final int LABEL_COLOR = 0xFFF2F2F2;
    private static final int MUTED_LABEL_COLOR = 0xFFC8C8C8;
    private static final int TITLE_COLOR = 0xFFFFFFFF;
    private static final int STATUS_COLOR = 0xFFD0D0D0;
    private static final int DIM_TEXT_COLOR = 0xFFA0A0A0;
    private static final int REACHED_WAYPOINT_COLOR = 0xFF88CC88;
    private static final int CURRENT_WAYPOINT_COLOR = 0xFFFFE28A;
    private static final int DEFAULT_WAYPOINT_COLOR = 0xFFD8D8D8;
    private static final int ROUTE_NAME_LABEL_X = 0;
    private static final int ROUTE_NAME_FIELD_WIDTH = 132;
    private static final int DEFAULT_ALTITUDE_FIELD_WIDTH = 64;
    private static final int LANDING_LABEL_X = 0;
    private static final int LANDING_ALTITUDE_FIELD_WIDTH = 56;
    private static final int WAYPOINT_LABEL_WIDTH = 88;
    private static final int FIELD_WIDTH = 48;
    private static final int FIELD_GAP = 4;
    private static final int FIELD_START_X = WAYPOINT_LABEL_WIDTH + 8;
    private static final int X_FIELD_X = FIELD_START_X;
    private static final int Z_FIELD_X = X_FIELD_X + FIELD_WIDTH + FIELD_GAP;
    private static final int ALTITUDE_FIELD_X = Z_FIELD_X + FIELD_WIDTH + FIELD_GAP;
    private static final int NAME_FIELD_X = ALTITUDE_FIELD_X + FIELD_WIDTH + FIELD_GAP + 8;
    private static final int NAME_FIELD_WIDTH = 112;
    private static final int DELETE_BUTTON_X = NAME_FIELD_X + NAME_FIELD_WIDTH + 6;
    private static final int UP_BUTTON_X = DELETE_BUTTON_X + 22;
    private static final int DOWN_BUTTON_X = UP_BUTTON_X + 22;

    private final int entityId;
    private CruiseRoute route;
    private final boolean readOnly;
    private final List<DraftWaypoint> waypoints = new ArrayList<>();
    private final List<EditBox> editBoxes = new ArrayList<>();
    private String routeName;
    private int defaultAltitude;
    private CruiseRoute.CruiseMode cruiseMode;
    private CruiseRoute.LandingMode landingMode;
    private Integer landingAltitude;
    private int scrollOffset;
    private EditBox routeNameBox;
    private EditBox defaultAltitudeBox;
    private EditBox landingAltitudeBox;
    private boolean dirty;
    private boolean confirmDeleteRoute;
    private boolean draggingHud;
    private boolean movingHud;
    private int dragOffsetX;
    private int dragOffsetY;

    public CruiseScreen(int entityId, CruiseRoute route) {
        this(entityId, route, false);
    }

    public CruiseScreen(int entityId, CruiseRoute route, boolean readOnly) {
        super(Component.translatable("screen.immersive_aircraft_cruise.title"));
        this.entityId = entityId;
        this.route = route.copy();
        this.readOnly = readOnly;
        loadSelectedRoute();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    public boolean isForEntity(int entityId) {
        return this.entityId == entityId;
    }

    @Override
    protected void init() {
        rebuildCruiseWidgets();
    }

    private void loadSelectedRoute() {
        CruiseRoute.RouteEntry entry = route.getSelectedEntry();
        routeName = entry.name();
        defaultAltitude = entry.defaultAltitude();
        cruiseMode = entry.cruiseMode();
        landingMode = entry.landingMode();
        landingAltitude = entry.landingAltitude();
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
        editBoxes.clear();
        routeNameBox = null;
        defaultAltitudeBox = null;
        landingAltitudeBox = null;

        int panelWidth = Math.min(PANEL_WIDTH, width - 24);
        int left = (width - panelWidth) / 2;
        int top = TOP_ROW_Y;
        FormRow routeRow = routeSettingsRow(left);

        addRenderableWidget(Button.builder(Component.literal("<"), button -> selectRoute(route.getSelectedRoute() - 1))
                .bounds(left, top, 22, 20).build());
        addRenderableWidget(Button.builder(Component.literal(">"), button -> selectRoute(route.getSelectedRoute() + 1))
                .bounds(left + 26, top, 22, 20).build());
        addWritableWidget(Button.builder(Component.translatable("screen.immersive_aircraft_cruise.new_route"), button -> {
            updateSelectedEntry();
            route.addRoute();
            loadSelectedRoute();
            confirmDeleteRoute = false;
            markDirty();
            autoSaveDraft();
            rebuildCruiseWidgets();
        }).bounds(left + 52, top, 50, 20).build());
        addWritableWidget(Button.builder(deleteRouteLabel(), button -> deleteRoute(button))
                .bounds(left + 106, top, 70, 20).build());
        addRenderableWidget(Button.builder(hudMoveLabel(), button -> {
            movingHud = !movingHud;
            button.setMessage(hudMoveLabel());
        }).bounds(left + panelWidth - 180, top, 92, 20).build());
        addWritableWidget(Button.builder(hudLabel(), button -> {
            toggleHud(button);
        }).bounds(left + panelWidth - 84, top, 84, 20).build());

        routeNameBox = new EditBox(font, routeRow.firstControlX(), ROUTE_SETTINGS_ROW_Y, ROUTE_NAME_FIELD_WIDTH, 20,
                Component.translatable("screen.immersive_aircraft_cruise.route_name"));
        routeNameBox.setValue(routeName);
        routeNameBox.setMaxLength(64);
        routeNameBox.setResponder(value -> {
            routeName = value;
            markDirty();
        });
        addEditBox(routeNameBox);

        defaultAltitudeBox = new EditBox(font, routeRow.secondControlX(), ROUTE_SETTINGS_ROW_Y, DEFAULT_ALTITUDE_FIELD_WIDTH, 20,
                Component.translatable("screen.immersive_aircraft_cruise.default_altitude"));
        defaultAltitudeBox.setValue(Integer.toString(defaultAltitude));
        defaultAltitudeBox.setFilter(CruiseScreen::isSignedIntegerText);
        defaultAltitudeBox.setResponder(value -> {
            updateDefaultAltitude();
            markDirty();
        });
        addEditBox(defaultAltitudeBox);

        Button cruiseModeButton = Button.builder(cruiseModeLabel(), button -> {
            cruiseMode = nextCruiseMode();
            button.setMessage(cruiseModeLabel());
            button.setTooltip(cruiseModeTooltip());
            button.setFGColor(cruiseModeTextColor());
            markDirty();
            autoSaveDraft();
        }).bounds(routeRow.thirdControlX(), ROUTE_SETTINGS_ROW_Y, cruiseModeButtonWidth(), 20)
                .tooltip(cruiseModeTooltip())
                .build();
        cruiseModeButton.setFGColor(cruiseModeTextColor());
        addWritableWidget(cruiseModeButton);

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

        int landingY = height - 52;
        FormRow landingRow = landingSettingsRow(left);
        Button landingModeButton = Button.builder(landingModeLabel(), button -> {
            landingMode = nextLandingMode();
            button.setMessage(landingModeLabel());
            button.setTooltip(landingModeTooltip());
            markDirty();
            autoSaveDraft();
        }).bounds(landingRow.firstControlX(), landingY, landingModeButtonWidth(), 20).build();
        landingModeButton.setTooltip(landingModeTooltip());
        addWritableWidget(landingModeButton);

        landingAltitudeBox = new EditBox(font, landingRow.secondControlX(), landingY, LANDING_ALTITUDE_FIELD_WIDTH, 20,
                Component.translatable("screen.immersive_aircraft_cruise.landing_altitude"));
        landingAltitudeBox.setFilter(CruiseScreen::isSignedIntegerText);
        landingAltitudeBox.setValue(landingAltitude == null ? "" : Integer.toString(landingAltitude));
        landingAltitudeBox.setTooltip(landingAltitudeTooltip());
        landingAltitudeBox.setResponder(value -> {
            updateLandingAltitude();
            landingModeButton.setMessage(landingModeLabel());
            landingModeButton.setTooltip(landingModeTooltip());
            markDirty();
        });
        addEditBox(landingAltitudeBox);

        int buttonY = height - 28;
        addWritableWidget(Button.builder(Component.translatable("screen.immersive_aircraft_cruise.add_current"), button -> addCurrentPosition())
                .bounds(left, buttonY, 86, 20).build());
        addWritableWidget(Button.builder(Component.translatable("screen.immersive_aircraft_cruise.clear"), button -> confirmClearWaypoints())
                .bounds(left + 92, buttonY, 58, 20).build());
        addWritableWidget(Button.builder(Component.translatable("screen.immersive_aircraft_cruise.save_refresh"), button -> saveRoute(true, true))
                .bounds(left + panelWidth - 164, buttonY, 98, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.immersive_aircraft_cruise.cancel"), button -> onClose())
                .bounds(left + panelWidth - 62, buttonY, 62, 20).build());
    }

    private Button addWritableWidget(Button button) {
        if (readOnly) {
            button.active = false;
        }
        return addRenderableWidget(button);
    }

    private EditBox addEditBox(EditBox editBox) {
        editBox.setEditable(!readOnly);
        if (readOnly) {
            editBox.active = false;
        }
        addRenderableWidget(editBox);
        editBoxes.add(editBox);
        return editBox;
    }

    private void addWaypointWidgets(int left, int y, int index) {
        DraftWaypoint waypoint = waypoints.get(index);
        EditBox xBox = editBox(left + X_FIELD_X, y, FIELD_WIDTH, waypoint.x);
        xBox.setResponder(value -> {
            waypoint.x = value;
            markDirty();
        });
        addEditBox(xBox);

        EditBox zBox = editBox(left + Z_FIELD_X, y, FIELD_WIDTH, waypoint.z);
        zBox.setResponder(value -> {
            waypoint.z = value;
            markDirty();
        });
        addEditBox(zBox);

        EditBox altitudeBox = editBox(left + ALTITUDE_FIELD_X, y, FIELD_WIDTH, waypoint.altitude);
        altitudeBox.setResponder(value -> {
            waypoint.altitude = value;
            markDirty();
        });
        addEditBox(altitudeBox);

        EditBox nameBox = new EditBox(font, left + NAME_FIELD_X, y, NAME_FIELD_WIDTH, 20, Component.empty());
        nameBox.setValue(waypoint.name);
        nameBox.setMaxLength(64);
        nameBox.setResponder(value -> {
            waypoint.name = value;
            markDirty();
        });
        addEditBox(nameBox);

        addWritableWidget(Button.builder(Component.literal("x"), button -> {
            waypoints.remove(index);
            scrollOffset = Math.min(scrollOffset, maxScrollOffset(visibleRows(104)));
            markDirty();
            autoSaveDraft();
            rebuildCruiseWidgets();
        }).bounds(left + DELETE_BUTTON_X, y, 18, 20).build());

        if (index > 0) {
            addWritableWidget(Button.builder(Component.literal("^"), button -> {
                swap(index, index - 1);
                markDirty();
                autoSaveDraft();
                rebuildCruiseWidgets();
            }).bounds(left + UP_BUTTON_X, y, 18, 20).build());
        }

        if (index + 1 < waypoints.size()) {
            addWritableWidget(Button.builder(Component.literal("v"), button -> {
                swap(index, index + 1);
                markDirty();
                autoSaveDraft();
                rebuildCruiseWidgets();
            }).bounds(left + DOWN_BUTTON_X, y, 18, 20).build());
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
        if (!readOnly) {
            updateSelectedEntry();
            autoSaveDraft();
        }
        route.setSelectedRouteForEditing(index);
        loadSelectedRoute();
        confirmDeleteRoute = false;
        scrollOffset = 0;
        rebuildCruiseWidgets();
    }

    private void deleteRoute(Button button) {
        if (readOnly) {
            return;
        }
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
        autoSaveDraft();
        rebuildCruiseWidgets();
    }

    private void addCurrentPosition() {
        if (readOnly) {
            return;
        }
        if (waypoints.size() >= CruiseRoute.MAX_WAYPOINTS || minecraft == null || minecraft.player == null) {
            return;
        }
        updateDefaultAltitude();
        BlockPos pos = minecraft.player.blockPosition();
        waypoints.add(new DraftWaypoint(Integer.toString(pos.getX()), Integer.toString(pos.getZ()), "", ""));
        scrollOffset = maxScrollOffset(visibleRows(104));
        markDirty();
        autoSaveDraft();
        rebuildCruiseWidgets();
    }

    private void confirmClearWaypoints() {
        if (readOnly) {
            return;
        }
        if (minecraft == null) {
            clearWaypoints();
            return;
        }
        minecraft.setScreen(new ConfirmScreen(confirmed -> {
            minecraft.setScreen(this);
            if (confirmed) {
                clearWaypoints();
            }
        }, Component.translatable("screen.immersive_aircraft_cruise.clear_confirm.title"),
                Component.translatable("screen.immersive_aircraft_cruise.clear_confirm.message")) {
            @Override
            public boolean isPauseScreen() {
                return false;
            }
        });
    }

    private void clearWaypoints() {
        if (readOnly) {
            return;
        }
        waypoints.clear();
        scrollOffset = 0;
        markDirty();
        autoSaveDraft();
        rebuildCruiseWidgets();
    }

    private void saveRoute(boolean refreshProgress, boolean showMessage) {
        if (readOnly) {
            return;
        }
        updateSelectedEntry();
        CruiseRoute draft = route.copy();
        CruiseRoute saved = draft.copy();
        CruiseRoute liveRoute = liveVehicleRoute();
        CruiseRoute liveSnapshot = liveRoute == null ? null : liveRoute.copy();
        if (refreshProgress) {
            saved.setEnabled(false);
            saved.resetProgress();
        } else if (liveSnapshot != null) {
            saved.applyRuntimeAndSelectionFrom(liveSnapshot);
        }
        if (minecraft != null && minecraft.level != null) {
            Entity entity = minecraft.level.getEntity(entityId);
            if (entity instanceof CruiseVehicleAccess access) {
                access.iacruise$setRoute(saved.copy());
            }
        }
        CruiseNetwork.sendToServer(new SyncCruiseRoutePacket(entityId, target(), saved, showMessage));
        if (refreshProgress) {
            route = saved;
        } else {
            route = draft;
            route.applyRuntimeFrom(liveSnapshot);
        }
        dirty = false;
    }

    private void autoSaveDraft() {
        if (readOnly) {
            return;
        }
        if (dirty) {
            saveRoute(false, false);
        }
    }

    private FormRow routeSettingsRow(int left) {
        Component routeNameLabel = Component.translatable("screen.immersive_aircraft_cruise.route_name");
        Component defaultAltitudeLabel = Component.translatable("screen.immersive_aircraft_cruise.default_altitude");
        int routeLabelX = left + ROUTE_NAME_LABEL_X;
        int routeFieldX = routeLabelX + font.width(routeNameLabel) + LABEL_CONTENT_GAP;
        int defaultLabelX = routeFieldX + ROUTE_NAME_FIELD_WIDTH + CONTROL_GAP;
        int defaultFieldX = defaultLabelX + font.width(defaultAltitudeLabel) + LABEL_CONTENT_GAP;
        int modeButtonX = defaultFieldX + DEFAULT_ALTITUDE_FIELD_WIDTH + CONTROL_GAP;
        return new FormRow(routeLabelX, routeFieldX, defaultLabelX, defaultFieldX, modeButtonX);
    }

    private FormRow landingSettingsRow(int left) {
        Component landingModeLabel = Component.translatable("screen.immersive_aircraft_cruise.landing_mode");
        Component landingAltitudeLabel = Component.translatable("screen.immersive_aircraft_cruise.landing_altitude");
        int landingLabelX = left + LANDING_LABEL_X;
        int landingButtonX = landingLabelX + font.width(landingModeLabel) + LABEL_CONTENT_GAP;
        int altitudeLabelX = landingButtonX + landingModeButtonWidth() + CONTROL_GAP;
        int altitudeFieldX = altitudeLabelX + font.width(landingAltitudeLabel) + LABEL_CONTENT_GAP;
        int hintX = altitudeFieldX + LANDING_ALTITUDE_FIELD_WIDTH + CONTROL_GAP;
        return new FormRow(landingLabelX, landingButtonX, altitudeLabelX, altitudeFieldX, hintX);
    }

    private int cruiseModeButtonWidth() {
        int textWidth = 0;
        for (CruiseRoute.CruiseMode mode : CruiseRoute.CruiseMode.values()) {
            textWidth = Math.max(textWidth, font.width(Component.translatable(
                    "screen.immersive_aircraft_cruise.cruise_mode." + mode.serializedName())));
        }
        return textWidth + BUTTON_HORIZONTAL_PADDING;
    }

    private int landingModeButtonWidth() {
        int textWidth = 0;
        for (CruiseRoute.LandingMode mode : CruiseRoute.LandingMode.values()) {
            textWidth = Math.max(textWidth, font.width(Component.translatable(
                    "screen.immersive_aircraft_cruise.landing_mode." + mode.serializedName())));
        }
        return textWidth + BUTTON_HORIZONTAL_PADDING;
    }

    private void commitFocusedEditBeforeMouseClick(double mouseX, double mouseY) {
        GuiEventListener focused = getFocused();
        if (focused instanceof EditBox focusedBox && !focusedBox.isMouseOver(mouseX, mouseY)) {
            autoSaveDraft();
        }
    }

    private record FormRow(int firstLabelX, int firstControlX, int secondLabelX, int secondControlX, int thirdControlX) {
    }

    public void updateRuntime(CruiseRoute syncedRoute) {
        if (syncedRoute == null || syncedRoute.getSelectedRoute() != route.getSelectedRoute()) {
            return;
        }
        if (dirty) {
            route.applyRuntimeFrom(syncedRoute);
            return;
        }
        route = syncedRoute.copy();
        loadSelectedRoute();
        rebuildCruiseWidgets();
    }

    private void updateSelectedEntry() {
        route.setSelectedEntry(buildSelectedEntry());
    }

    private CruiseRoute.RouteEntry buildSelectedEntry() {
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
        updateLandingAltitude();
        String name = routeName == null || routeName.isBlank() ? "Route " + (route.getSelectedRoute() + 1) : routeName.trim();
        return new CruiseRoute.RouteEntry(name, defaultAltitude, cruiseMode, effectiveLandingModeFromWaypoints(routeWaypoints), landingAltitude, routeWaypoints);
    }

    private int visibleRows(int listTop) {
        return Math.max(1, (height - listTop - 60) / ROW_HEIGHT);
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

    private void updateLandingAltitude() {
        if (landingAltitudeBox == null) {
            return;
        }
        Integer parsed = parseInteger(landingAltitudeBox.getValue());
        landingAltitude = parsed == null ? null : clamp(parsed, -64, 512);
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

    private Component landingModeLabel() {
        return Component.translatable("screen.immersive_aircraft_cruise.landing_mode.value",
                Component.translatable("screen.immersive_aircraft_cruise.landing_mode." + effectiveLandingModeFromDrafts().serializedName()));
    }

    private Component cruiseModeLabel() {
        return Component.translatable("screen.immersive_aircraft_cruise.cruise_mode.value",
                Component.translatable("screen.immersive_aircraft_cruise.cruise_mode." + effectiveCruiseMode().serializedName()));
    }

    private Tooltip cruiseModeTooltip() {
        return Tooltip.create(Component.translatable("screen.immersive_aircraft_cruise.cruise_mode."
                + effectiveCruiseMode().serializedName() + ".tooltip"));
    }

    private Tooltip landingModeTooltip() {
        return switch (effectiveLandingModeFromDrafts()) {
            case FASTEST -> Tooltip.create(Component.translatable(
                    "screen.immersive_aircraft_cruise.landing_mode.fastest.tooltip"));
            case VERTICAL -> Tooltip.create(Component.translatable(
                    "screen.immersive_aircraft_cruise.landing_mode.vertical.tooltip"));
            default -> null;
        };
    }

    private Tooltip landingAltitudeTooltip() {
        return Tooltip.create(Component.translatable("screen.immersive_aircraft_cruise.landing_altitude.tooltip"));
    }

    private CruiseRoute.CruiseMode nextCruiseMode() {
        CruiseRoute.CruiseMode[] modes = CruiseRoute.CruiseMode.values();
        return modes[(effectiveCruiseMode().ordinal() + 1) % modes.length];
    }

    private CruiseRoute.CruiseMode effectiveCruiseMode() {
        return cruiseMode == null ? CruiseRoute.CruiseMode.SUPER_ACCELERATION : cruiseMode;
    }

    private CruiseRoute.LandingMode nextLandingMode() {
        CruiseRoute.LandingMode[] modes = CruiseRoute.LandingMode.values();
        int index = (landingMode.ordinal() + 1) % modes.length;
        CruiseRoute.LandingMode next = modes[index];
        updateLandingAltitude();
        if (next.requiresFinalAltitude() && landingAltitude == null) {
            return CruiseRoute.LandingMode.HOLDING_PATTERN;
        }
        return next;
    }

    private CruiseRoute.LandingMode effectiveLandingModeFromWaypoints(List<CruiseRoute.Waypoint> routeWaypoints) {
        updateLandingAltitude();
        if (landingMode == null || landingMode.requiresFinalAltitude()
                && (routeWaypoints.isEmpty() || landingAltitude == null)) {
            return CruiseRoute.LandingMode.HOLDING_PATTERN;
        }
        return landingMode;
    }

    private CruiseRoute.LandingMode effectiveLandingModeFromDrafts() {
        updateLandingAltitude();
        if (landingMode == null || landingMode.requiresFinalAltitude()
                && (waypoints.isEmpty() || landingAltitude == null)) {
            return CruiseRoute.LandingMode.HOLDING_PATTERN;
        }
        return landingMode;
    }

    private boolean draftHasLandingAltitude() {
        updateLandingAltitude();
        return landingAltitude != null;
    }

    private void markDirty() {
        if (readOnly) {
            return;
        }
        dirty = true;
    }

    private void toggleHud(Button button) {
        if (readOnly) {
            return;
        }
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
            CruiseNetwork.sendToServer(new UpdateCruiseHudPacket(entityId, target(), hudEnabled));
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (movingHud && button == 0 && isInsideHud(mouseX, mouseY)) {
            commitFocusedEditBeforeMouseClick(mouseX, mouseY);
            draggingHud = true;
            dragOffsetX = (int) mouseX - CruiseHud.x(width);
            dragOffsetY = (int) mouseY - CruiseHud.y(height);
            return true;
        }
        commitFocusedEditBeforeMouseClick(mouseX, mouseY);
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
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        autoSaveDraft();
        int listTop = 104;
        int visibleRows = visibleRows(listTop);
        int next = clamp(scrollOffset - (int) Math.signum(scrollY), 0, maxScrollOffset(visibleRows));
        if (next != scrollOffset) {
            scrollOffset = next;
            rebuildCruiseWidgets();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        CruiseRoute previewRoute = previewRoute();
        int panelWidth = Math.min(PANEL_WIDTH, width - 24);
        int left = (width - panelWidth) / 2;
        FormRow routeRow = routeSettingsRow(left);
        FormRow landingRow = landingSettingsRow(left);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawString(font, title, left, 10, TITLE_COLOR, true);
        drawRouteStatus(graphics, previewRoute, left, panelWidth);
        graphics.drawString(font, Component.literal((route.getSelectedRoute() + 1) + "/" + route.getRoutes().size()).withStyle(ChatFormatting.GRAY), left + 180, 34, DIM_TEXT_COLOR, false);
        graphics.drawString(font, Component.translatable("screen.immersive_aircraft_cruise.route_name"),
                routeRow.firstLabelX(), ROUTE_SETTINGS_ROW_Y + 6, LABEL_COLOR, true);
        graphics.drawString(font, Component.translatable("screen.immersive_aircraft_cruise.default_altitude"),
                routeRow.secondLabelX(), ROUTE_SETTINGS_ROW_Y + 6, LABEL_COLOR, true);
        graphics.drawString(font, Component.literal("X"), left + X_FIELD_X, 92, MUTED_LABEL_COLOR, true);
        graphics.drawString(font, Component.literal("Z"), left + Z_FIELD_X, 92, MUTED_LABEL_COLOR, true);
        graphics.drawString(font, Component.translatable("screen.immersive_aircraft_cruise.segment_altitude"), left + ALTITUDE_FIELD_X, 92, MUTED_LABEL_COLOR, true);
        graphics.drawString(font, Component.translatable("screen.immersive_aircraft_cruise.waypoint_name"), left + NAME_FIELD_X, 92, MUTED_LABEL_COLOR, true);

        int listTop = 104;
        int visibleRows = visibleRows(listTop);
        for (int row = 0; row < visibleRows; row++) {
            int index = scrollOffset + row;
            if (index >= waypoints.size()) {
                break;
            }
            int y = listTop + row * ROW_HEIGHT + 6;
            boolean reached = previewRoute.isWaypointReached(index);
            boolean current = previewRoute.isWaypointCurrent(index);
            String label = (reached ? "● " : current ? "▶ " : "• ") + routeDisplayName(index);
            graphics.drawString(font, Component.literal(label), left, y, reached ? REACHED_WAYPOINT_COLOR : current ? CURRENT_WAYPOINT_COLOR : DEFAULT_WAYPOINT_COLOR, false);
        }
        if (waypoints.size() > visibleRows) {
            graphics.drawString(font, Component.literal((scrollOffset + 1) + "-" + Math.min(waypoints.size(), scrollOffset + visibleRows) + "/" + waypoints.size()).withStyle(ChatFormatting.GRAY),
                    left + panelWidth - 54, 92, DIM_TEXT_COLOR, false);
        }
        graphics.drawString(font, Component.translatable("screen.immersive_aircraft_cruise.landing_mode"),
                landingRow.firstLabelX(), height - 47, LABEL_COLOR, true);
        graphics.drawString(font, Component.translatable("screen.immersive_aircraft_cruise.landing_altitude"),
                landingRow.secondLabelX(), height - 47, LABEL_COLOR, true);
        if (!draftHasLandingAltitude()) {
            graphics.drawString(font, Component.translatable("screen.immersive_aircraft_cruise.landing_mode.requires_y").withStyle(ChatFormatting.GRAY),
                    landingRow.thirdControlX(), height - 47, DIM_TEXT_COLOR, false);
        }
        renderHudPreview(graphics, previewRoute);
    }

    private String routeDisplayName(int index) {
        if (index < 0 || index >= waypoints.size()) {
            return "Waypoint " + (index + 1);
        }
        DraftWaypoint waypoint = waypoints.get(index);
        String name = normalizeText(waypoint.name);
        if (!name.isBlank()) {
            return name;
        }
        String x = normalizeText(waypoint.x);
        String z = normalizeText(waypoint.z);
        if (!x.isBlank() && !z.isBlank()) {
            return x + "," + z;
        }
        return "Waypoint " + (index + 1);
    }

    private void drawRouteStatus(GuiGraphics graphics, CruiseRoute previewRoute, int left, int panelWidth) {
        CruiseRoute liveRoute = liveVehicleRoute();
        CruiseRoute statusRoute = liveRoute == null ? previewRoute : liveRoute;
        String nextStop = statusPointLabel(statusNextPoint(statusRoute));
        String routeName = statusRoute.getSelectedRouteDisplayName();
        String text = routeName.isBlank()
                ? Component.translatable("screen.immersive_aircraft_cruise.next_stop_status", nextStop).getString()
                : Component.translatable("screen.immersive_aircraft_cruise.route_status", routeName, nextStop).getString();
        int x = left + 126;
        int maxWidth = Math.max(0, panelWidth - 126);
        graphics.drawString(font, fitText(text, maxWidth), x, 10, STATUS_COLOR, false);
    }

    private int cruiseModeTextColor() {
        return switch (effectiveCruiseMode()) {
            case SUPER_ACCELERATION -> 0xFFFFD36A;
            case NORMAL -> 0xFF74B9FF;
            case ECO -> 0xFF7DFF8A;
        };
    }

    private CruiseRoute.Waypoint statusNextPoint(CruiseRoute statusRoute) {
        CruiseRoute.Waypoint target = statusRoute.getTarget();
        if (target != null) {
            return target;
        }
        CruiseRoute.Waypoint current = statusRoute.getCurrentWaypoint();
        return current == null ? statusRoute.getFinalTarget() : current;
    }

    private String statusPointLabel(CruiseRoute.Waypoint waypoint) {
        return waypoint == null ? "-" : waypoint.displayLabel();
    }

    private String fitText(String text, int maxWidth) {
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

    private CruiseRoute previewRoute() {
        CruiseRoute preview = route.copy();
        preview.setSelectedEntry(buildSelectedEntry());
        CruiseRoute liveRoute = liveVehicleRoute();
        if (liveRoute != null) {
            preview.applyRuntimeFrom(liveRoute);
        }
        return preview;
    }

    private CruiseRoute liveVehicleRoute() {
        if (minecraft == null || minecraft.level == null || entityId < 0) {
            return null;
        }
        Entity entity = minecraft.level.getEntity(entityId);
        if (entity instanceof CruiseVehicleAccess access) {
            return access.iacruise$getRoute();
        }
        return null;
    }

    private RouteStorageTarget target() {
        return entityId < 0 ? RouteStorageTarget.HELD_MODULE : RouteStorageTarget.VEHICLE_MODULE;
    }

    private boolean isInsideHud(double mouseX, double mouseY) {
        int x = CruiseHud.x(width);
        int y = CruiseHud.y(height);
        return mouseX >= x && mouseX < x + CruiseHud.width() && mouseY >= y && mouseY < y + CruiseHud.height();
    }

    private void renderHudPreview(GuiGraphics graphics, CruiseRoute previewRoute) {
        if (!movingHud || minecraft == null || minecraft.level == null || entityId < 0) {
            return;
        }
        Entity entity = minecraft.level.getEntity(entityId);
        if (entity instanceof VehicleEntity vehicle && entity instanceof CruiseVehicleAccess access) {
            CruiseHud.renderHud(graphics, font, vehicle, access, previewRoute, width, height);
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
