package com.g1739.immersiveaircraftcruise.cruise;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;

public class CruiseRoute {
    public static final int MAX_ROUTES = 16;
    public static final int MAX_WAYPOINTS = 32;
    public static final double LANDING_ALTITUDE_INPUT_OFFSET = 1.5d;

    private boolean enabled;
    private boolean holdingPattern;
    private boolean hudEnabled;
    private boolean initialAltitudeReached;
    private int selectedRoute;
    private int currentIndex;
    private Waypoint startPoint;
    private final List<RouteEntry> routes;

    public CruiseRoute(boolean enabled, boolean holdingPattern, boolean hudEnabled, int selectedRoute, int currentIndex, List<RouteEntry> routes) {
        this(enabled, holdingPattern, hudEnabled, selectedRoute, currentIndex, null, currentIndex > 0 || holdingPattern, routes);
    }

    public CruiseRoute(boolean enabled, boolean holdingPattern, boolean hudEnabled, int selectedRoute, int currentIndex, Waypoint startPoint, List<RouteEntry> routes) {
        this(enabled, holdingPattern, hudEnabled, selectedRoute, currentIndex, startPoint, currentIndex > 0 || holdingPattern, routes);
    }

    public CruiseRoute(boolean enabled, boolean holdingPattern, boolean hudEnabled, int selectedRoute, int currentIndex, Waypoint startPoint,
                       boolean initialAltitudeReached, List<RouteEntry> routes) {
        this.enabled = enabled;
        this.holdingPattern = holdingPattern;
        this.hudEnabled = hudEnabled;
        this.initialAltitudeReached = initialAltitudeReached;
        this.selectedRoute = Math.max(0, selectedRoute);
        this.currentIndex = Math.max(0, currentIndex);
        this.startPoint = startPoint;
        this.routes = new ArrayList<>(routes.stream().limit(MAX_ROUTES).toList());
        if (this.routes.isEmpty()) {
            this.routes.add(RouteEntry.empty(defaultRouteName(1)));
        }
        clampSelectedRoute();
        clampCurrentIndex();
    }

    public static CruiseRoute empty() {
        return new CruiseRoute(false, false, true, 0, 0, List.of(RouteEntry.empty(defaultRouteName(1))));
    }

    public CruiseRoute copy() {
        return new CruiseRoute(enabled, holdingPattern, hudEnabled, selectedRoute, currentIndex, startPoint, initialAltitudeReached, routes);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void stopNavigation() {
        enabled = false;
    }

    public void resume(Waypoint startPointIfMissing) {
        if (startPoint == null && startPointIfMissing != null) {
            startPoint = startPointIfMissing;
        }
        enabled = true;
    }

    public boolean isHoldingPattern() {
        return holdingPattern;
    }

    public void setHoldingPattern(boolean holdingPattern) {
        this.holdingPattern = holdingPattern;
        if (holdingPattern) {
            initialAltitudeReached = true;
        }
    }

    public boolean isHudEnabled() {
        return hudEnabled;
    }

    public void setHudEnabled(boolean hudEnabled) {
        this.hudEnabled = hudEnabled;
    }

    public int getSelectedRoute() {
        return selectedRoute;
    }

    public void setSelectedRoute(int selectedRoute) {
        this.selectedRoute = Math.max(0, selectedRoute);
        clampSelectedRoute();
        resetProgress();
    }

    public void setSelectedRouteForEditing(int selectedRoute) {
        this.selectedRoute = Math.max(0, selectedRoute);
        clampSelectedRoute();
        clampCurrentIndex();
    }

    public int getCurrentIndex() {
        return currentIndex;
    }

    public void setCurrentIndex(int currentIndex) {
        this.currentIndex = Math.max(0, currentIndex);
        clampCurrentIndex();
    }

    public boolean isInitialAltitudeReached() {
        return initialAltitudeReached;
    }

    public void setInitialAltitudeReached(boolean initialAltitudeReached) {
        this.initialAltitudeReached = initialAltitudeReached;
    }

    public Waypoint getStartPoint() {
        return startPoint;
    }

    public boolean hasStartPoint() {
        return startPoint != null;
    }

    public void setStartPoint(Waypoint startPoint) {
        this.startPoint = startPoint;
    }

    public List<RouteEntry> getRoutes() {
        return routes;
    }

    public RouteEntry getSelectedEntry() {
        clampSelectedRoute();
        return routes.get(selectedRoute);
    }

    public String getSelectedRouteDisplayName() {
        return routeNameForDisplay(getSelectedEntry().name(), selectedRoute + 1);
    }

    public static String routeNameForDisplay(String name, int index) {
        String normalized = name == null ? "" : name.strip();
        if (normalized.isBlank() || normalized.equals(defaultRouteName(index))) {
            return "";
        }
        return normalized;
    }

    public boolean hasAnyWaypoint() {
        for (RouteEntry route : routes) {
            if (!route.waypoints().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public void setSelectedEntry(RouteEntry entry) {
        clampSelectedRoute();
        routes.set(selectedRoute, entry == null ? RouteEntry.empty(defaultRouteName(selectedRoute + 1)) : entry);
        clampCurrentIndex();
        if (getSelectedEntry().waypoints().isEmpty()) {
            enabled = false;
            holdingPattern = false;
            startPoint = null;
        }
    }

    public void addRoute() {
        if (routes.size() >= MAX_ROUTES) {
            return;
        }
        routes.add(RouteEntry.empty(defaultRouteName(routes.size() + 1)));
        selectedRoute = routes.size() - 1;
        resetProgress();
    }

    public void removeSelectedRoute() {
        if (routes.size() <= 1) {
            setSelectedEntry(RouteEntry.empty(defaultRouteName(1)));
            resetProgress();
            return;
        }
        routes.remove(selectedRoute);
        if (selectedRoute >= routes.size()) {
            selectedRoute = routes.size() - 1;
        }
        resetProgress();
    }

    public boolean hasTarget() {
        return enabled && !holdingPattern && currentIndex >= 0 && currentIndex < getSelectedEntry().waypoints().size();
    }

    public Waypoint getTarget() {
        return hasTarget() ? getCurrentWaypoint() : null;
    }

    public Waypoint getCurrentWaypoint() {
        RouteEntry entry = getSelectedEntry();
        if (currentIndex < 0 || currentIndex >= entry.waypoints().size()) {
            return null;
        }
        return entry.waypoints().get(currentIndex);
    }

    public boolean isWaypointReached(int index) {
        int size = getSelectedEntry().waypoints().size();
        if (index < 0 || index >= size) {
            return false;
        }
        return index < currentIndex || (holdingPattern && index == currentIndex);
    }

    public boolean isWaypointCurrent(int index) {
        int size = getSelectedEntry().waypoints().size();
        return !holdingPattern && index >= 0 && index < size && index == currentIndex;
    }

    public Waypoint getPreviousWaypoint() {
        if (holdingPattern) {
            return getFinalTarget();
        }
        RouteEntry entry = getSelectedEntry();
        if (entry.waypoints().isEmpty() || currentIndex <= 0) {
            return null;
        }
        int index = Math.min(Math.max(0, currentIndex - 1), entry.waypoints().size() - 1);
        return entry.waypoints().get(index);
    }

    public Waypoint getFinalTarget() {
        List<Waypoint> waypoints = getSelectedEntry().waypoints();
        return waypoints.isEmpty() ? null : waypoints.get(waypoints.size() - 1);
    }

    public int getTargetAltitude() {
        Waypoint target = getTarget();
        if (target == null) {
            target = getCurrentWaypoint();
        }
        if (target == null) {
            return getSelectedEntry().defaultAltitude();
        }
        if (!initialAltitudeReached) {
            return getSelectedEntry().defaultAltitude();
        }
        return altitudeFor(target);
    }

    public double getFinalAltitude() {
        RouteEntry entry = getSelectedEntry();
        if (getEffectiveLandingMode() != LandingMode.HOLDING_PATTERN && entry.hasLandingAltitude()) {
            return entry.landingAltitude() + LANDING_ALTITUDE_INPUT_OFFSET;
        }
        return getFinalFlightAltitude();
    }

    public int getFinalFlightAltitude() {
        Waypoint finalTarget = getFinalTarget();
        return finalTarget != null ? altitudeFor(finalTarget) : getSelectedEntry().defaultAltitude();
    }

    public int getSegmentStartAltitude() {
        RouteEntry entry = getSelectedEntry();
        if (currentIndex <= 0 || entry.waypoints().isEmpty()) {
            return entry.defaultAltitude();
        }
        int previousIndex = Math.min(currentIndex - 1, entry.waypoints().size() - 1);
        return altitudeFor(entry.waypoints().get(previousIndex));
    }

    public int altitudeFor(Waypoint waypoint) {
        return waypoint != null && waypoint.hasAltitudeOverride() ? waypoint.altitude() : getSelectedEntry().defaultAltitude();
    }

    public boolean isFinalTarget() {
        return hasTarget() && currentIndex == getSelectedEntry().waypoints().size() - 1;
    }

    public LandingMode getLandingMode() {
        return getSelectedEntry().landingMode();
    }

    public CruiseMode getCruiseMode() {
        return getSelectedEntry().cruiseMode();
    }

    public LandingMode getEffectiveLandingMode() {
        LandingMode mode = getLandingMode();
        return mode.requiresFinalAltitude() && !hasFinalAltitudeOverride() ? LandingMode.HOLDING_PATTERN : mode;
    }

    public boolean hasFinalAltitudeOverride() {
        return getSelectedEntry().hasLandingAltitude();
    }

    public void advance() {
        currentIndex++;
        if (currentIndex >= getSelectedEntry().waypoints().size()) {
            holdingPattern = true;
            currentIndex = Math.max(0, getSelectedEntry().waypoints().size() - 1);
            initialAltitudeReached = true;
        }
    }

    public void resetProgress() {
        currentIndex = 0;
        holdingPattern = false;
        startPoint = null;
        initialAltitudeReached = false;
        if (getSelectedEntry().waypoints().isEmpty()) {
            enabled = false;
        }
    }

    public void applyRuntimeFrom(CruiseRoute source) {
        if (source == null) {
            return;
        }
        hudEnabled = source.hudEnabled;
        if (source.selectedRoute != selectedRoute) {
            return;
        }
        enabled = source.enabled;
        holdingPattern = source.holdingPattern;
        currentIndex = source.currentIndex;
        startPoint = source.startPoint;
        initialAltitudeReached = source.initialAltitudeReached;
        clampCurrentIndex();
    }

    public void applyRuntimeAndSelectionFrom(CruiseRoute source) {
        if (source == null) {
            return;
        }
        hudEnabled = source.hudEnabled;
        selectedRoute = source.selectedRoute;
        clampSelectedRoute();
        enabled = source.enabled;
        holdingPattern = source.holdingPattern;
        currentIndex = source.currentIndex;
        startPoint = source.startPoint;
        initialAltitudeReached = source.initialAltitudeReached;
        clampCurrentIndex();
    }

    public boolean mergeProgressForwardFrom(CruiseRoute source) {
        if (source == null || source.selectedRoute != selectedRoute) {
            return false;
        }
        return mergeProgressForward(source.currentIndex, source.holdingPattern, source.initialAltitudeReached, source.startPoint);
    }

    public boolean mergeProgressForward(int sourceCurrentIndex, boolean sourceHoldingPattern,
                                        boolean sourceInitialAltitudeReached, Waypoint sourceStartPoint) {
        boolean changed = false;
        int waypointCount = getSelectedEntry().waypoints().size();
        if (waypointCount > 0 && sourceCurrentIndex >= 0 && sourceCurrentIndex < waypointCount) {
            if (sourceCurrentIndex > currentIndex) {
                currentIndex = sourceCurrentIndex;
                holdingPattern = false;
                changed = true;
            }
            if (sourceHoldingPattern && sourceCurrentIndex == waypointCount - 1
                    && (!holdingPattern || currentIndex != sourceCurrentIndex)) {
                currentIndex = sourceCurrentIndex;
                holdingPattern = true;
                changed = true;
            }
        }
        if (sourceInitialAltitudeReached && !initialAltitudeReached) {
            initialAltitudeReached = true;
            changed = true;
        }
        if (startPoint == null && sourceStartPoint != null) {
            startPoint = sourceStartPoint;
            changed = true;
        }
        clampCurrentIndex();
        return changed;
    }

    private void clampSelectedRoute() {
        if (selectedRoute >= routes.size()) {
            selectedRoute = routes.size() - 1;
        }
    }

    private void clampCurrentIndex() {
        int size = getSelectedEntry().waypoints().size();
        if (size == 0) {
            currentIndex = 0;
            holdingPattern = false;
            initialAltitudeReached = false;
        } else if (currentIndex >= size) {
            currentIndex = size - 1;
        }
    }

    public void write(FriendlyByteBuf buffer) {
        buffer.writeBoolean(enabled);
        buffer.writeBoolean(holdingPattern);
        buffer.writeBoolean(hudEnabled);
        buffer.writeInt(selectedRoute);
        buffer.writeInt(currentIndex);
        buffer.writeBoolean(startPoint != null);
        if (startPoint != null) {
            startPoint.write(buffer);
        }
        buffer.writeBoolean(initialAltitudeReached);
        buffer.writeInt(routes.size());
        for (RouteEntry route : routes) {
            route.write(buffer);
        }
    }

    public static CruiseRoute read(FriendlyByteBuf buffer) {
        boolean enabled = buffer.readBoolean();
        boolean holdingPattern = buffer.readBoolean();
        boolean hudEnabled = buffer.readBoolean();
        int selectedRoute = buffer.readInt();
        int currentIndex = buffer.readInt();
        Waypoint startPoint = buffer.readBoolean() ? Waypoint.read(buffer) : null;
        boolean initialAltitudeReached = buffer.readBoolean();
        int size = Math.min(buffer.readInt(), MAX_ROUTES);
        List<RouteEntry> routes = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            routes.add(RouteEntry.read(buffer));
        }
        return new CruiseRoute(enabled, holdingPattern, hudEnabled, selectedRoute, currentIndex, startPoint, initialAltitudeReached, routes);
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("Enabled", enabled);
        tag.putBoolean("HoldingPattern", holdingPattern);
        tag.putBoolean("HudEnabled", hudEnabled);
        tag.putInt("SelectedRoute", selectedRoute);
        tag.putInt("CurrentIndex", currentIndex);
        if (startPoint != null) {
            tag.put("StartPoint", startPoint.toTag());
        }
        tag.putBoolean("InitialAltitudeReached", initialAltitudeReached);
        ListTag list = new ListTag();
        for (RouteEntry route : routes) {
            list.add(route.toTag());
        }
        tag.put("Routes", list);
        return tag;
    }

    public static CruiseRoute fromTag(CompoundTag tag) {
        boolean enabled = tag.getBoolean("Enabled");
        if (tag.getBoolean("Paused")) {
            enabled = false;
        }
        ListTag list = tag.getList("Routes", Tag.TAG_COMPOUND);
        List<RouteEntry> routes = new ArrayList<>(Math.min(list.size(), MAX_ROUTES));
        for (int i = 0; i < list.size() && i < MAX_ROUTES; i++) {
            routes.add(RouteEntry.fromTag(list.getCompound(i), i + 1));
        }
        return new CruiseRoute(
                enabled,
                tag.getBoolean("HoldingPattern"),
                !tag.contains("HudEnabled", Tag.TAG_BYTE) || tag.getBoolean("HudEnabled"),
                tag.getInt("SelectedRoute"),
                tag.getInt("CurrentIndex"),
                tag.contains("StartPoint", Tag.TAG_COMPOUND) ? Waypoint.fromTag(tag.getCompound("StartPoint")) : null,
                tag.contains("InitialAltitudeReached", Tag.TAG_BYTE)
                        ? tag.getBoolean("InitialAltitudeReached")
                        : tag.getInt("CurrentIndex") > 0 || tag.getBoolean("HoldingPattern"),
                routes
        );
    }

    private static String defaultRouteName(int index) {
        return "Route " + index;
    }

    public enum LandingMode {
        HOLDING_PATTERN(0, "holding_pattern"),
        FASTEST(1, "fastest"),
        VERTICAL(2, "vertical");

        private final int id;
        private final String serializedName;

        LandingMode(int id, String serializedName) {
            this.id = id;
            this.serializedName = serializedName;
        }

        public int id() {
            return id;
        }

        public String serializedName() {
            return serializedName;
        }

        public boolean requiresFinalAltitude() {
            return this != HOLDING_PATTERN;
        }

        public static LandingMode byId(int id) {
            for (LandingMode mode : values()) {
                if (mode.id == id) {
                    return mode;
                }
            }
            return HOLDING_PATTERN;
        }

        public static LandingMode byName(String name) {
            for (LandingMode mode : values()) {
                if (mode.serializedName.equals(name)) {
                    return mode;
                }
            }
            return HOLDING_PATTERN;
        }
    }

    public enum CruiseMode {
        SUPER_ACCELERATION(0, "super_acceleration", 1.0f, 3.0f),
        NORMAL(1, "normal", 0.2f, 0.0f),
        ECO(2, "eco", -0.15f, -0.75f);

        private final int id;
        private final String serializedName;
        private final float powerBonus;
        private final float fuelBonus;

        CruiseMode(int id, String serializedName, float powerBonus, float fuelBonus) {
            this.id = id;
            this.serializedName = serializedName;
            this.powerBonus = powerBonus;
            this.fuelBonus = fuelBonus;
        }

        public int id() {
            return id;
        }

        public String serializedName() {
            return serializedName;
        }

        public float powerBonus() {
            return powerBonus;
        }

        public float fuelBonus() {
            return fuelBonus;
        }

        public static CruiseMode byId(int id) {
            for (CruiseMode mode : values()) {
                if (mode.id == id) {
                    return mode;
                }
            }
            return SUPER_ACCELERATION;
        }

        public static CruiseMode byName(String name) {
            for (CruiseMode mode : values()) {
                if (mode.serializedName.equals(name)) {
                    return mode;
                }
            }
            return SUPER_ACCELERATION;
        }
    }

    public record RouteEntry(String name, int defaultAltitude, CruiseMode cruiseMode, LandingMode landingMode,
                             Integer landingAltitude, List<Waypoint> waypoints) {
        public static RouteEntry empty(String name) {
            return new RouteEntry(name, 200, CruiseMode.SUPER_ACCELERATION, LandingMode.HOLDING_PATTERN, null, List.of());
        }

        public RouteEntry(String name, int defaultAltitude, LandingMode landingMode, Integer landingAltitude, List<Waypoint> waypoints) {
            this(name, defaultAltitude, CruiseMode.SUPER_ACCELERATION, landingMode, landingAltitude, waypoints);
        }

        public RouteEntry {
            if (name == null || name.isBlank()) {
                name = defaultRouteName(1);
            }
            if (cruiseMode == null) {
                cruiseMode = CruiseMode.SUPER_ACCELERATION;
            }
            if (landingMode == null) {
                landingMode = LandingMode.HOLDING_PATTERN;
            }
            waypoints = new ArrayList<>(waypoints.stream().limit(MAX_WAYPOINTS).toList());
            if (landingMode.requiresFinalAltitude() && (waypoints.isEmpty() || landingAltitude == null)) {
                landingMode = LandingMode.HOLDING_PATTERN;
            }
        }

        public boolean hasLandingAltitude() {
            return landingAltitude != null;
        }

        public void write(FriendlyByteBuf buffer) {
            buffer.writeUtf(name, 64);
            buffer.writeInt(defaultAltitude);
            buffer.writeInt(cruiseMode.id());
            buffer.writeInt(landingMode.id());
            buffer.writeBoolean(landingAltitude != null);
            if (landingAltitude != null) {
                buffer.writeInt(landingAltitude);
            }
            buffer.writeInt(waypoints.size());
            for (Waypoint waypoint : waypoints) {
                waypoint.write(buffer);
            }
        }

        public static RouteEntry read(FriendlyByteBuf buffer) {
            String name = buffer.readUtf(64);
            int defaultAltitude = buffer.readInt();
            CruiseMode cruiseMode = CruiseMode.byId(buffer.readInt());
            LandingMode landingMode = LandingMode.byId(buffer.readInt());
            Integer landingAltitude = buffer.readBoolean() ? buffer.readInt() : null;
            int size = Math.min(buffer.readInt(), MAX_WAYPOINTS);
            List<Waypoint> waypoints = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                waypoints.add(Waypoint.read(buffer));
            }
            return new RouteEntry(name, defaultAltitude, cruiseMode, landingMode, landingAltitude, waypoints);
        }

        public CompoundTag toTag() {
            CompoundTag tag = new CompoundTag();
            tag.putString("Name", name);
            tag.putInt("DefaultAltitude", defaultAltitude);
            tag.putString("CruiseMode", cruiseMode.serializedName());
            tag.putString("LandingMode", landingMode.serializedName());
            if (landingAltitude != null) {
                tag.putInt("LandingAltitude", landingAltitude);
            }
            ListTag list = new ListTag();
            for (Waypoint waypoint : waypoints) {
                list.add(waypoint.toTag());
            }
            tag.put("Waypoints", list);
            return tag;
        }

        public static RouteEntry fromTag(CompoundTag tag, int index) {
            ListTag list = tag.getList("Waypoints", Tag.TAG_COMPOUND);
            List<Waypoint> waypoints = new ArrayList<>(Math.min(list.size(), MAX_WAYPOINTS));
            for (int i = 0; i < list.size() && i < MAX_WAYPOINTS; i++) {
                waypoints.add(Waypoint.fromTag(list.getCompound(i)));
            }
            String name = tag.contains("Name", Tag.TAG_STRING) ? tag.getString("Name") : defaultRouteName(index);
            int defaultAltitude = tag.contains("DefaultAltitude", Tag.TAG_INT) ? tag.getInt("DefaultAltitude") : 200;
            CruiseMode cruiseMode = tag.contains("CruiseMode", Tag.TAG_STRING)
                    ? CruiseMode.byName(tag.getString("CruiseMode"))
                    : CruiseMode.SUPER_ACCELERATION;
            LandingMode landingMode = tag.contains("LandingMode", Tag.TAG_STRING)
                    ? LandingMode.byName(tag.getString("LandingMode"))
                    : LandingMode.HOLDING_PATTERN;
            Integer landingAltitude = tag.contains("LandingAltitude", Tag.TAG_INT) ? tag.getInt("LandingAltitude") : null;
            if (landingAltitude == null && landingMode.requiresFinalAltitude() && !waypoints.isEmpty()) {
                Waypoint finalWaypoint = waypoints.get(waypoints.size() - 1);
                if (finalWaypoint.hasAltitudeOverride()) {
                    landingAltitude = finalWaypoint.altitude();
                    waypoints.set(waypoints.size() - 1, new Waypoint(finalWaypoint.x(), finalWaypoint.z(), null, finalWaypoint.name()));
                }
            }
            return new RouteEntry(name, defaultAltitude, cruiseMode, landingMode, landingAltitude, waypoints);
        }
    }

    public record Waypoint(int x, int z, Integer altitude, String name) {
        public Waypoint(int x, int z, Integer altitude) {
            this(x, z, altitude, null);
        }

        public Waypoint {
            name = name == null ? "" : name.strip();
        }

        public boolean hasAltitudeOverride() {
            return altitude != null;
        }

        public boolean hasName() {
            return !name.isBlank();
        }

        public String displayLabel() {
            return hasName() ? name : x + "," + z;
        }

        public void write(FriendlyByteBuf buffer) {
            buffer.writeInt(x);
            buffer.writeInt(z);
            buffer.writeBoolean(altitude != null);
            if (altitude != null) {
                buffer.writeInt(altitude);
            }
            buffer.writeUtf(name, 64);
        }

        public static Waypoint read(FriendlyByteBuf buffer) {
            int x = buffer.readInt();
            int z = buffer.readInt();
            Integer altitude = buffer.readBoolean() ? buffer.readInt() : null;
            String name = buffer.readUtf(64);
            return new Waypoint(x, z, altitude, name);
        }

        public CompoundTag toTag() {
            CompoundTag tag = new CompoundTag();
            tag.putInt("X", x);
            tag.putInt("Z", z);
            if (altitude != null) {
                tag.putInt("Altitude", altitude);
            }
            tag.putString("Name", name);
            return tag;
        }

        public static Waypoint fromTag(CompoundTag tag) {
            Integer altitude = tag.contains("Altitude", Tag.TAG_INT) ? tag.getInt("Altitude") : null;
            String name = tag.contains("Name", Tag.TAG_STRING) ? tag.getString("Name") : "";
            return new Waypoint(tag.getInt("X"), tag.getInt("Z"), altitude, name);
        }
    }
}
