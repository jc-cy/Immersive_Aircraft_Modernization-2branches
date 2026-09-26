package com.g1739.immersiveaircraftcruise;

import org.slf4j.Logger;

/**
 * Single source switch for high-volume development diagnostics.
 *
 * <p>Change {@link #ENABLED} to {@code true} for a temporary diagnostic build,
 * then set it back to {@code false} before producing a clean jar. The switch
 * is intentionally independent from the JVM's JDWP option.</p>
 */
public final class CruiseDebug {
    /** Temporary developer switch. Set false before producing a clean release jar. */
    public static final boolean ENABLED = false;

    /**
     * Landing diagnostics switch (temporary developer switch, high volume).
     *
     * <p>Kept separate from {@link #ENABLED} so a landing investigation can read only the landing lines instead of
     * the chunk/route flood. Set both back to {@code false} before producing a clean release jar.</p>
     */
    public static final boolean LANDING = false;

    private CruiseDebug() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    public static boolean landingEnabled() {
        return LANDING || ENABLED;
    }

    public static void debug(Logger logger, String message, Object... arguments) {
        if (ENABLED) {
            logger.debug(message, arguments);
        }
    }

    public static void info(Logger logger, String message, Object... arguments) {
        if (ENABLED) {
            logger.info(message, arguments);
        }
    }

    public static void landing(Logger logger, String message, Object... arguments) {
        if (landingEnabled()) {
            logger.info(message, arguments);
        }
    }

}
