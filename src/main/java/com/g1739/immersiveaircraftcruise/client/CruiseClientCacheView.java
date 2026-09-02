package com.g1739.immersiveaircraftcruise.client;

public final class CruiseClientCacheView {
    private static final int NO_ROUTE_RADIUS_OVERRIDE = Integer.MIN_VALUE;
    private static int radius = Integer.MIN_VALUE;
    private static int centerX = Integer.MIN_VALUE;
    private static int centerZ = Integer.MIN_VALUE;
    private static int routeRadiusOverride = NO_ROUTE_RADIUS_OVERRIDE;

    private CruiseClientCacheView() {
    }

    public static int radius() {
        return radius;
    }

    public static int centerX() {
        return centerX;
    }

    public static int centerZ() {
        return centerZ;
    }

    public static void setRadius(int value) {
        radius = value;
    }

    /**
     * Keeps the client-side route window from being replaced by a later vanilla
     * cache-radius packet while navigation is active.
     */
    public static void setRouteRadiusOverride(int value) {
        routeRadiusOverride = Math.max(2, value);
    }

    public static void clearRouteRadiusOverride() {
        routeRadiusOverride = NO_ROUTE_RADIUS_OVERRIDE;
    }

    public static int routeRadiusOr(int requested) {
        return routeRadiusOverride == NO_ROUTE_RADIUS_OVERRIDE ? requested : routeRadiusOverride;
    }

    public static void setCenter(int x, int z) {
        centerX = x;
        centerZ = z;
    }
}
