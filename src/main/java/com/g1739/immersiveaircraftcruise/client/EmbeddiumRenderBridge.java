package com.g1739.immersiveaircraftcruise.client;

import com.g1739.immersiveaircraftcruise.ImmersiveAircraftCruise;
import net.minecraft.client.multiplayer.ClientLevel;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bridges combined chunk packets to Embeddium's existing chunk readiness tracker. */
public final class EmbeddiumRenderBridge {
    private static final Method TRACKER_GET;
    private static final Method TRACKER_STATUS_ADDED;
    private static final Method RENDERER_INSTANCE;
    private static final Field RENDER_SECTION_MANAGER;
    private static final Method RENDER_SECTION_MANAGER_CHUNK_ADDED;
    private static final AtomicBoolean FIRST_DIRECT_SECTION = new AtomicBoolean();
    private static final AtomicBoolean DIRECT_SECTION_FAILURE_LOGGED = new AtomicBoolean();

    static {
        Method trackerGet = null;
        Method trackerStatusAdded = null;
        Method rendererInstance = null;
        Field renderSectionManager = null;
        Method renderSectionManagerChunkAdded = null;
        try {
            Class<?> holder = Class.forName(
                    "me.jellysquid.mods.sodium.client.render.chunk.map.ChunkTrackerHolder");
            trackerGet = holder.getMethod("get", ClientLevel.class);
            Class<?> tracker = Class.forName(
                    "me.jellysquid.mods.sodium.client.render.chunk.map.ChunkTracker");
            trackerStatusAdded = tracker.getMethod("onChunkStatusAdded", int.class, int.class, int.class);

            Class<?> renderer = Class.forName(
                    "me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer");
            rendererInstance = renderer.getMethod("instanceNullable");
            renderSectionManager = renderer.getDeclaredField("renderSectionManager");
            renderSectionManager.setAccessible(true);
            Class<?> sectionManager = Class.forName(
                    "me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager");
            renderSectionManagerChunkAdded = sectionManager.getMethod("onChunkAdded", int.class, int.class);
        } catch (ReflectiveOperationException ignored) {
            // Embeddium is optional; vanilla rendering has no tracker to update.
        }
        TRACKER_GET = trackerGet;
        TRACKER_STATUS_ADDED = trackerStatusAdded;
        RENDERER_INSTANCE = rendererInstance;
        RENDER_SECTION_MANAGER = renderSectionManager;
        RENDER_SECTION_MANAGER_CHUNK_ADDED = renderSectionManagerChunkAdded;
        ImmersiveAircraftCruise.LOGGER.info(
                "[CruiseRender] Embeddium bridge {} (tracker={}, directSections={})",
                rendererInstance != null && renderSectionManagerChunkAdded != null ? "available" : "unavailable",
                trackerGet != null && trackerStatusAdded != null,
                rendererInstance != null && renderSectionManagerChunkAdded != null);
    }

    private EmbeddiumRenderBridge() {
    }

    /** Creates the render sections immediately for a received route chunk. */
    public static void markChunkVisible(int chunkX, int chunkZ) {
        if (RENDERER_INSTANCE == null
                || RENDER_SECTION_MANAGER == null
                || RENDER_SECTION_MANAGER_CHUNK_ADDED == null) {
            return;
        }
        try {
            Object renderer = RENDERER_INSTANCE.invoke(null);
            if (renderer == null) {
                return;
            }
            Object sectionManager = RENDER_SECTION_MANAGER.get(renderer);
            if (sectionManager == null) {
                return;
            }
            RENDER_SECTION_MANAGER_CHUNK_ADDED.invoke(sectionManager, chunkX, chunkZ);
            if (FIRST_DIRECT_SECTION.compareAndSet(false, true)) {
                ImmersiveAircraftCruise.LOGGER.info(
                        "[CruiseRender] direct Embeddium section creation active at chunk={}/{}",
                        chunkX, chunkZ);
            }
        } catch (ReflectiveOperationException exception) {
            if (DIRECT_SECTION_FAILURE_LOGGED.compareAndSet(false, true)) {
                ImmersiveAircraftCruise.LOGGER.warn(
                        "[CruiseRender] direct Embeddium section creation failed", exception);
            }
        }
    }

    public static void markLightReady(ClientLevel level, int chunkX, int chunkZ) {
        if (TRACKER_GET == null || TRACKER_STATUS_ADDED == null) {
            return;
        }
        try {
            Object tracker = TRACKER_GET.invoke(null, level);
            TRACKER_STATUS_ADDED.invoke(tracker, chunkX, chunkZ, 2);
        } catch (ReflectiveOperationException exception) {
            // Keep the route packet usable if a different optional renderer changes its tracker API.
        }
    }
}
