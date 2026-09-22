package com.g1739.immersiveaircraftcruise.client;


import com.g1739.immersiveaircraftcruise.ImmersiveAircraftCruise;
import net.minecraft.client.multiplayer.ClientLevel;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bridges combined chunk packets to Embeddium's existing chunk readiness tracker. */
public final class EmbeddiumRenderBridge {
    /**
     * 1.20 Embeddium and 1.21 Embeddium (1.0.x, Sodium 0.6 based) live in different packages, so the
     * optional integration looks for both. Nothing is required to be present: vanilla rendering and
     * the route packets work exactly the same when no candidate resolves.
     */
    private static final String[] CHUNK_TRACKER_HOLDERS = {
            "me.jellysquid.mods.sodium.client.render.chunk.map.ChunkTrackerHolder",
            "org.embeddedt.embeddium.impl.render.chunk.map.ChunkTrackerHolder",
    };
    private static final String[] CHUNK_TRACKERS = {
            "me.jellysquid.mods.sodium.client.render.chunk.map.ChunkTracker",
            "org.embeddedt.embeddium.impl.render.chunk.map.ChunkTracker",
    };
    private static final String[] WORLD_RENDERERS = {
            "me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer",
            // Embeddium 1.0.x (Sodium 0.6 based, used on 1.21.1) renamed the world renderer.
            "org.embeddedt.embeddium.impl.render.EmbeddiumWorldRenderer",
    };
    private static final String[] RENDER_SECTION_MANAGERS = {
            "me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager",
            "org.embeddedt.embeddium.impl.render.chunk.RenderSectionManager",
    };

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
        for (String holderName : CHUNK_TRACKER_HOLDERS) {
            for (String trackerName : CHUNK_TRACKERS) {
                try {
                    Class<?> holder = Class.forName(holderName);
                    Class<?> tracker = Class.forName(trackerName);
                    trackerGet = holder.getMethod("get", ClientLevel.class);
                    trackerStatusAdded = tracker.getMethod("onChunkStatusAdded", int.class, int.class, int.class);
                    break;
                } catch (ReflectiveOperationException ignored) {
                    trackerGet = null;
                    trackerStatusAdded = null;
                }
            }
            if (trackerGet != null) {
                break;
            }
        }
        for (String rendererName : WORLD_RENDERERS) {
            for (String sectionManagerName : RENDER_SECTION_MANAGERS) {
                try {
                    Class<?> renderer = Class.forName(rendererName);
                    Class<?> sectionManager = Class.forName(sectionManagerName);
                    rendererInstance = renderer.getMethod("instanceNullable");
                    renderSectionManager = renderer.getDeclaredField("renderSectionManager");
                    renderSectionManager.setAccessible(true);
                    renderSectionManagerChunkAdded = sectionManager.getMethod("onChunkAdded", int.class, int.class);
                    break;
                } catch (ReflectiveOperationException ignored) {
                    rendererInstance = null;
                    renderSectionManager = null;
                    renderSectionManagerChunkAdded = null;
                }
            }
            if (rendererInstance != null) {
                break;
            }
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
