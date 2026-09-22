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
    public static final double L_SHAPED_MIN_HORIZONTAL_DISTANCE = 512.0d;
    /** An offset below one chunk is treated as an already-determined center line. */
    public static final double L_SHAPED_MIN_SHORT_AXIS_DISTANCE = 16.0d;
    public static final double L_FINAL_LANDING_HANDOFF_DISTANCE = 512.0d;
    public static final double LANDING_ALTITUDE_INPUT_OFFSET = 0.0d;

    public static final int L_STAGE_AXIS_ALIGN = 0;
    public static final int L_STAGE_CENTERLINE_CAPTURE = 1;
    public static final int L_STAGE_FIRST_LEG = 2;
    public static final int L_STAGE_CORNER_TURN = 3;
    public static final int L_STAGE_FINAL_LEG = 4;
    public static final int L_STAGE_MAX = L_STAGE_FINAL_LEG;

    private boolean enabled;
    private boolean holdingPattern;
    private boolean hudEnabled;
    private boolean initialAltitudeReached;
    private int selectedRoute;
    private int currentIndex;
    /**
     * L-shaped runtime state. The value is persisted so the client and
     * server cannot silently fall back to a diagonal route after a pause.
     */
    private int loadingStage;
    /** The lateral chunk-center line selected when the first axis reaches the 45 degree gate. */
    private Integer lFirstLineCoordinate;
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
        this(enabled, holdingPattern, hudEnabled, selectedRoute, currentIndex, startPoint,
                initialAltitudeReached, 0, routes);
    }

    public CruiseRoute(boolean enabled, boolean holdingPattern, boolean hudEnabled, int selectedRoute, int currentIndex, Waypoint startPoint,
                       boolean initialAltitudeReached, int loadingStage, List<RouteEntry> routes) {
        this(enabled, holdingPattern, hudEnabled, selectedRoute, currentIndex, startPoint,
                initialAltitudeReached, loadingStage, null, routes);
    }

    public CruiseRoute(boolean enabled, boolean holdingPattern, boolean hudEnabled, int selectedRoute, int currentIndex, Waypoint startPoint,
                       boolean initialAltitudeReached, int loadingStage, Integer lFirstLineCoordinate, List<RouteEntry> routes) {
        this.enabled = enabled;
        this.holdingPattern = holdingPattern;
        this.hudEnabled = hudEnabled;
        this.initialAltitudeReached = initialAltitudeReached;
        this.selectedRoute = Math.max(0, selectedRoute);
        this.currentIndex = Math.max(0, currentIndex);
        this.loadingStage = Math.max(0, Math.min(L_STAGE_MAX, loadingStage));
        this.lFirstLineCoordinate = lFirstLineCoordinate;
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
        return new CruiseRoute(enabled, holdingPattern, hudEnabled, selectedRoute, currentIndex, startPoint,
                initialAltitudeReached, loadingStage, lFirstLineCoordinate, routes);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void stopNavigation() {
        enabled = false;
        // A later navigation run must select its lateral line from the
        // current takeoff context; keep the route and progress intact.
        lFirstLineCoordinate = null;
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

    public int getLoadingStage() {
        return loadingStage;
    }

    public void setLoadingStage(int loadingStage) {
        this.loadingStage = Math.max(0, Math.min(L_STAGE_MAX, loadingStage));
    }

    public Integer getLFirstLineCoordinate() {
        return lFirstLineCoordinate;
    }

    public void setLFirstLineCoordinate(Integer lFirstLineCoordinate) {
        this.lFirstLineCoordinate = lFirstLineCoordinate;
    }

    public boolean isLShapedSingleMode() {
        return getSelectedEntry().loadingMode() == RouteLoadingMode.L_SHAPED_SINGLE;
    }

    /**
     * Returns whether the current waypoint leg needs the five-stage L route.
     *
     * The classification is intentionally based on the fixed geometry of the
     * current leg, not on the remaining vector every tick. Once a leg is
     * classified, it must not silently switch between L and direct mode while
     * the aircraft is travelling along it; the controller and the preload
     * scheduler both consume this result.
     */
    public boolean shouldUseLShaped(double currentX, double currentZ) {
        if (!isLShapedSingleMode()) {
            return false;
        }
        Waypoint start = lStartPoint();
        Waypoint target = getTarget();
        if (start == null || target == null) {
            return false;
        }
        double dx = target.x() - start.x();
        double dz = target.z() - start.z();
        double shortAxisDistance = Math.min(Math.abs(dx), Math.abs(dz));
        return isLongLRouteCandidate()
                && dx != 0.0d
                && dz != 0.0d
                && shortAxisDistance >= L_SHAPED_MIN_SHORT_AXIS_DISTANCE;
    }

    /**
     * True for a long L-mode leg, including an axis-aligned leg whose short
     * axis is already zero. Such a leg still needs the endpoint chunk's long
     * axis center line, but it does not need the L five-stage turn.
     */
    public boolean isLongLRouteCandidate() {
        Waypoint target = getTarget();
        Waypoint start = lStartPoint();
        if (start == null || target == null || !isLShapedSingleMode()) {
            return false;
        }
        return Math.hypot(target.x() - start.x(), target.z() - start.z())
                > L_SHAPED_MIN_HORIZONTAL_DISTANCE;
    }

    private Waypoint lStartPoint() {
        return currentIndex <= 0 ? startPoint : getPreviousWaypoint();
    }

    public boolean isLFirstAxisX() {
        Waypoint start = lStartPoint();
        Waypoint target = getTarget();
        return start != null && target != null
                && Math.abs(target.x() - start.x()) <= Math.abs(target.z() - start.z());
    }

    public int lFirstAxisSign() {
        Waypoint start = lStartPoint();
        Waypoint target = getTarget();
        if (start == null || target == null) {
            return 1;
        }
        int delta = isLFirstAxisX() ? target.x() - start.x() : target.z() - start.z();
        return delta == 0 ? 1 : Integer.signum(delta);
    }

    public int lSecondAxisSign() {
        Waypoint start = lStartPoint();
        Waypoint target = getTarget();
        if (start == null || target == null) {
            return 1;
        }
        int delta = isLFirstAxisX() ? target.z() - start.z() : target.x() - start.x();
        return delta == 0 ? 1 : Integer.signum(delta);
    }

    private static int chunkCenter(int block) {
        return Math.floorDiv(block, 16) * 16 + 8;
    }

    private static int selectedStartLineCenter(int block) {
        // The nearest line minimizes the time from takeoff to the first
        // usable one-wide route. A low-angle crossing is handled by the
        // controller and snaps the aircraft onto this line.
        return chunkCenter(block);
    }

    private Waypoint lCornerTarget() {
        Waypoint start = lStartPoint();
        Waypoint target = getTarget();
        if (start == null || target == null) {
            return target;
        }
        int lateralLine = lFirstLineCoordinate != null
                ? lFirstLineCoordinate
                : isLFirstAxisX()
                ? selectedStartLineCenter(start.z())
                : selectedStartLineCenter(start.x());
        int targetXCenter = chunkCenter(target.x());
        int targetZCenter = chunkCenter(target.z());
        if (isLFirstAxisX()) {
            return new Waypoint(targetXCenter, lateralLine, target.altitude(), target.name());
        }
        return new Waypoint(lateralLine, targetZCenter, target.altitude(), target.name());
    }

    public Waypoint getLCornerTarget() {
        return lCornerTarget();
    }

    public Waypoint getLFinalLineTarget() {
        return lFinalLineTarget();
    }

    private Waypoint lFinalLineTarget() {
        Waypoint target = getTarget();
        if (target == null) {
            return null;
        }
        if (isLFirstAxisX()) {
            return new Waypoint(chunkCenter(target.x()), target.z(), target.altitude(), target.name());
        }
        return new Waypoint(target.x(), chunkCenter(target.z()), target.altitude(), target.name());
    }

    /**
     * Returns the geometric target for the current L state. Stage 0 is a
     * free axis-only launch; stages 1-2 capture and follow the first leg;
     * stage 3 turns at the corner; stage 4 follows the final axis line.
     */
    public Waypoint getLNavigationTarget(double currentX, double currentZ) {
        Waypoint target = getTarget();
        if (target == null) {
            return null;
        }
        if (loadingStage == L_STAGE_AXIS_ALIGN) {
            if (isLFirstAxisX()) {
                return new Waypoint(target.x(), (int) Math.floor(currentZ), target.altitude(), target.name());
            }
            return new Waypoint((int) Math.floor(currentX), target.z(), target.altitude(), target.name());
        }
        if (loadingStage <= L_STAGE_CORNER_TURN) {
            return lCornerTarget();
        }
        return lFinalLineTarget();
    }

    /** Returns the first center line in the lateral travel direction at or beyond the exact distance. */
    public int selectLFirstLine(double currentX, double currentZ, double requiredDistance) {
        double current = isLFirstAxisX() ? currentZ : currentX;
        int direction = lSecondAxisSign();
        int line = chunkCenter((int) Math.floor(current));
        double distance = Math.max(0.0d, requiredDistance);
        if (direction > 0) {
            while (line - current < distance) {
                line += 16;
            }
        } else {
            while (current - line < distance) {
                line -= 16;
            }
        }
        return line;
    }

    /**
     * Returns the steering/preload target for the current leg. A long leg
     * whose fixed short-axis offset is below one chunk skips the L stages and
     * uses the endpoint chunk's long-axis center line directly.
     */
    public Waypoint getNavigationTarget(double currentX, double currentZ) {
        Waypoint target = getTarget();
        if (target == null || !isLShapedSingleMode()) {
            return target;
        }
        boolean useLShaped = shouldUseLShaped(currentX, currentZ);
        if (isLongLRouteCandidate() && !useLShaped) {
            return lFinalLineTarget();
        }
        return useLShaped ? getLNavigationTarget(currentX, currentZ) : target;
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

    /**
     * Estimates the remaining horizontal route distance from the supplied
     * vehicle position. L-shaped legs use their two-axis path length instead
     * of the direct diagonal distance; altitude and landing manoeuvres are
     * intentionally ignored because this is a lightweight HUD estimate.
     */
    public double remainingHorizontalDistance(double currentX, double currentZ) {
        List<Waypoint> waypoints = getSelectedEntry().waypoints();
        if (waypoints.isEmpty() || holdingPattern) {
            return 0.0d;
        }
        int index = Math.max(0, Math.min(currentIndex, waypoints.size() - 1));
        Waypoint target = waypoints.get(index);
        double distance = segmentDistance(currentX, currentZ, target.x() + 0.5d, target.z() + 0.5d);
        for (int i = index + 1; i < waypoints.size(); i++) {
            Waypoint previous = waypoints.get(i - 1);
            Waypoint next = waypoints.get(i);
            distance += segmentDistance(previous.x() + 0.5d, previous.z() + 0.5d,
                    next.x() + 0.5d, next.z() + 0.5d);
        }
        return Math.max(0.0d, distance);
    }

    private double segmentDistance(double startX, double startZ, double targetX, double targetZ) {
        double dx = targetX - startX;
        double dz = targetZ - startZ;
        if (isLShapedSingleMode() && dx != 0.0d && dz != 0.0d
                && Math.hypot(dx, dz) > L_SHAPED_MIN_HORIZONTAL_DISTANCE) {
            return Math.abs(dx) + Math.abs(dz);
        }
        return Math.hypot(dx, dz);
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

    public boolean isNavigationFinished() {
        List<Waypoint> waypoints = getSelectedEntry().waypoints();
        return !waypoints.isEmpty()
                && !enabled
                && holdingPattern
                && currentIndex >= waypoints.size() - 1;
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
        loadingStage = 0;
        lFirstLineCoordinate = null;
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
        loadingStage = 0;
        lFirstLineCoordinate = null;
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
        loadingStage = source.loadingStage;
        lFirstLineCoordinate = source.lFirstLineCoordinate;
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
        loadingStage = source.loadingStage;
        lFirstLineCoordinate = source.lFirstLineCoordinate;
        clampCurrentIndex();
    }

    public boolean mergeProgressForwardFrom(CruiseRoute source) {
        if (source == null || source.selectedRoute != selectedRoute) {
            return false;
        }
        return mergeProgressForward(source.currentIndex, source.holdingPattern, source.initialAltitudeReached,
                source.loadingStage, source.startPoint, source.lFirstLineCoordinate);
    }

    public boolean mergeProgressForward(int sourceCurrentIndex, boolean sourceHoldingPattern,
                                        boolean sourceInitialAltitudeReached, Waypoint sourceStartPoint) {
        return mergeProgressForward(sourceCurrentIndex, sourceHoldingPattern, sourceInitialAltitudeReached, 0, sourceStartPoint);
    }

    public boolean mergeProgressForward(int sourceCurrentIndex, boolean sourceHoldingPattern,
                                        boolean sourceInitialAltitudeReached, int sourceLoadingStage,
                                        Waypoint sourceStartPoint) {
        return mergeProgressForward(sourceCurrentIndex, sourceHoldingPattern, sourceInitialAltitudeReached,
                sourceLoadingStage, sourceStartPoint, null);
    }

    public boolean mergeProgressForward(int sourceCurrentIndex, boolean sourceHoldingPattern,
                                        boolean sourceInitialAltitudeReached, int sourceLoadingStage,
                                        Waypoint sourceStartPoint, Integer sourceLFirstLineCoordinate) {
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
        if (sourceCurrentIndex == currentIndex && sourceLoadingStage > loadingStage) {
            loadingStage = Math.min(L_STAGE_MAX, sourceLoadingStage);
            changed = true;
        }
        if (sourceCurrentIndex == currentIndex && sourceLFirstLineCoordinate != null
                && !sourceLFirstLineCoordinate.equals(lFirstLineCoordinate)) {
            lFirstLineCoordinate = sourceLFirstLineCoordinate;
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
        buffer.writeInt(loadingStage);
        buffer.writeBoolean(lFirstLineCoordinate != null);
        if (lFirstLineCoordinate != null) {
            buffer.writeInt(lFirstLineCoordinate);
        }
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
        int loadingStage = buffer.readInt();
        Integer lFirstLineCoordinate = buffer.readBoolean() ? buffer.readInt() : null;
        int size = buffer.readInt();
        if (size < 0 || size > MAX_ROUTES) {
            throw new IllegalArgumentException("Cruise route count exceeds protocol limit: " + size);
        }
        List<RouteEntry> routes = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            RouteEntry route = RouteEntry.read(buffer);
            routes.add(route);
        }
        return new CruiseRoute(enabled, holdingPattern, hudEnabled, selectedRoute, currentIndex, startPoint,
                initialAltitudeReached, loadingStage, lFirstLineCoordinate, routes);
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
        tag.putInt("LoadingStage", loadingStage);
        if (lFirstLineCoordinate != null) {
            tag.putInt("LFirstLineCoordinate", lFirstLineCoordinate);
        }
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
                tag.contains("LoadingStage", Tag.TAG_INT) ? tag.getInt("LoadingStage") : 0,
                tag.contains("LFirstLineCoordinate", Tag.TAG_INT) ? tag.getInt("LFirstLineCoordinate") : null,
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
        ECO(2, "eco", -0.15f, -0.75f),
        ACCELERATION(3, "acceleration", 0.6f, 1.5f);

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
            return (float) CruiseConfig.powerBonus(this, powerBonus);
        }

        public float fuelBonus() {
            return (float) CruiseConfig.fuelBonus(this, fuelBonus);
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

    public enum RouteLoadingMode {
        VANILLA(0, "vanilla"),
        THREE_WIDE(1, "three_wide"),
        L_SHAPED_SINGLE(2, "l_shaped_single");

        private final int id;
        private final String serializedName;

        RouteLoadingMode(int id, String serializedName) {
            this.id = id;
            this.serializedName = serializedName;
        }

        public int id() {
            return id;
        }

        public String serializedName() {
            return serializedName;
        }

        public static RouteLoadingMode byId(int id) {
            for (RouteLoadingMode mode : values()) {
                if (mode.id == id) {
                    return mode;
                }
            }
            return THREE_WIDE;
        }

        public static RouteLoadingMode byName(String name) {
            for (RouteLoadingMode mode : values()) {
                if (mode.serializedName.equals(name)) {
                    return mode;
                }
            }
            return THREE_WIDE;
        }
    }

    public record RouteEntry(String name, int defaultAltitude, CruiseMode cruiseMode, RouteLoadingMode loadingMode,
                             LandingMode landingMode,
                             Integer landingAltitude, List<Waypoint> waypoints) {
        public static RouteEntry empty(String name) {
            return new RouteEntry(name, 200, CruiseMode.SUPER_ACCELERATION, RouteLoadingMode.THREE_WIDE,
                    LandingMode.HOLDING_PATTERN, null, List.of());
        }

        public RouteEntry(String name, int defaultAltitude, LandingMode landingMode, Integer landingAltitude, List<Waypoint> waypoints) {
            this(name, defaultAltitude, CruiseMode.SUPER_ACCELERATION, RouteLoadingMode.THREE_WIDE,
                    landingMode, landingAltitude, waypoints);
        }

        public RouteEntry(String name, int defaultAltitude, CruiseMode cruiseMode, LandingMode landingMode,
                          Integer landingAltitude, List<Waypoint> waypoints) {
            this(name, defaultAltitude, cruiseMode, RouteLoadingMode.THREE_WIDE, landingMode, landingAltitude, waypoints);
        }

        public RouteEntry {
            if (name == null || name.isBlank()) {
                name = defaultRouteName(1);
            }
            if (cruiseMode == null) {
                cruiseMode = CruiseMode.SUPER_ACCELERATION;
            }
            if (loadingMode == null) {
                // Old route NBT did not contain a loading mode; preserve its three-wide behavior.
                loadingMode = RouteLoadingMode.THREE_WIDE;
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
            buffer.writeInt(loadingMode.id());
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
            RouteLoadingMode loadingMode = RouteLoadingMode.byId(buffer.readInt());
            LandingMode landingMode = LandingMode.byId(buffer.readInt());
            Integer landingAltitude = buffer.readBoolean() ? buffer.readInt() : null;
            int size = buffer.readInt();
            if (size < 0 || size > MAX_WAYPOINTS) {
                throw new IllegalArgumentException("Cruise waypoint count exceeds protocol limit: " + size);
            }
            List<Waypoint> waypoints = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                Waypoint waypoint = Waypoint.read(buffer);
                waypoints.add(waypoint);
            }
            return new RouteEntry(name, defaultAltitude, cruiseMode, loadingMode, landingMode, landingAltitude, waypoints);
        }

        public CompoundTag toTag() {
            CompoundTag tag = new CompoundTag();
            tag.putString("Name", name);
            tag.putInt("DefaultAltitude", defaultAltitude);
            tag.putString("CruiseMode", cruiseMode.serializedName());
            tag.putString("LoadingMode", loadingMode.serializedName());
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
            RouteLoadingMode loadingMode = tag.contains("LoadingMode", Tag.TAG_STRING)
                    ? RouteLoadingMode.byName(tag.getString("LoadingMode"))
                    : RouteLoadingMode.THREE_WIDE;
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
            return new RouteEntry(name, defaultAltitude, cruiseMode, loadingMode, landingMode, landingAltitude, waypoints);
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
