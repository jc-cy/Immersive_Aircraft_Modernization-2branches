package com.g1739.immersiveaircraftcruise.client;

import com.g1739.immersiveaircraftcruise.CruiseDebug;
import com.g1739.immersiveaircraftcruise.ImmersiveAircraftCruise;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bridges combined chunk packets to Embeddium's existing chunk readiness tracker. */
public final class EmbeddiumRenderBridge {
    private static final Method TRACKER_GET;
    private static final Method TRACKER_STATUS_ADDED;
    private static final Method TRACKER_READY_CHUNKS;
    private static final Method READY_CHUNKS_CONTAINS;
    private static final Method RENDERER_INSTANCE;
    private static final Field RENDER_SECTION_MANAGER;
    private static final Method RENDER_SECTION_MANAGER_CHUNK_ADDED;
    private static final Method RENDER_SECTION_MANAGER_SECTION_COUNT;
    private static final AtomicBoolean FIRST_DIRECT_SECTION = new AtomicBoolean();
    private static final AtomicBoolean DIRECT_SECTION_FAILURE_LOGGED = new AtomicBoolean();

    /** Diagnostic tallies, drained once per second by {@link #logSummary()}. */
    private static int visibleCalls;
    private static int visibleSectionsCreated;
    private static int visibleUntracked;
    private static int visibleUnavailable;
    private static int lightCalls;
    private static int failures;

    static {
        Method trackerGet = null;
        Method trackerStatusAdded = null;
        Method trackerReadyChunks = null;
        Method readyChunksContains = null;
        Method rendererInstance = null;
        Field renderSectionManager = null;
        Method renderSectionManagerChunkAdded = null;
        Method renderSectionManagerSectionCount = null;
        try {
            Class<?> holder = Class.forName(
                    "me.jellysquid.mods.sodium.client.render.chunk.map.ChunkTrackerHolder");
            trackerGet = holder.getMethod("get", ClientLevel.class);
            Class<?> tracker = Class.forName(
                    "me.jellysquid.mods.sodium.client.render.chunk.map.ChunkTracker");
            trackerStatusAdded = tracker.getMethod("onChunkStatusAdded", int.class, int.class, int.class);
            trackerReadyChunks = tracker.getMethod("getReadyChunks");
            Class<?> longCollection = Class.forName("it.unimi.dsi.fastutil.longs.LongCollection");
            readyChunksContains = longCollection.getMethod("contains", long.class);

            Class<?> renderer = Class.forName(
                    "me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer");
            rendererInstance = renderer.getMethod("instanceNullable");
            renderSectionManager = renderer.getDeclaredField("renderSectionManager");
            renderSectionManager.setAccessible(true);
            Class<?> sectionManager = Class.forName(
                    "me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager");
            renderSectionManagerChunkAdded = sectionManager.getMethod("onChunkAdded", int.class, int.class);
            renderSectionManagerSectionCount = sectionManager.getMethod("getTotalSections");
        } catch (ReflectiveOperationException ignored) {
            // Embeddium is optional; vanilla rendering has no tracker to update.
        }
        TRACKER_GET = trackerGet;
        TRACKER_STATUS_ADDED = trackerStatusAdded;
        TRACKER_READY_CHUNKS = trackerReadyChunks;
        READY_CHUNKS_CONTAINS = readyChunksContains;
        RENDERER_INSTANCE = rendererInstance;
        RENDER_SECTION_MANAGER = renderSectionManager;
        RENDER_SECTION_MANAGER_CHUNK_ADDED = renderSectionManagerChunkAdded;
        RENDER_SECTION_MANAGER_SECTION_COUNT = renderSectionManagerSectionCount;
        CruiseDebug.info(ImmersiveAircraftCruise.LOGGER,
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
            visibleUnavailable++;
            return;
        }
        try {
            Object renderer = RENDERER_INSTANCE.invoke(null);
            if (renderer == null) {
                visibleUnavailable++;
                return;
            }
            Object sectionManager = RENDER_SECTION_MANAGER.get(renderer);
            if (sectionManager == null) {
                visibleUnavailable++;
                return;
            }
            int sectionsBefore = sectionCount(sectionManager);
            boolean trackedBefore = isTrackerReady(chunkX, chunkZ);
            RENDER_SECTION_MANAGER_CHUNK_ADDED.invoke(sectionManager, chunkX, chunkZ);
            int sectionsAfter = sectionCount(sectionManager);
            visibleCalls++;
            visibleSectionsCreated += Math.max(0, sectionsAfter - sectionsBefore);
            if (!trackedBefore) {
                visibleUntracked++;
                CruiseDebug.debug(ImmersiveAircraftCruise.LOGGER,
                        "[CruiseRender] section created while Embeddium has not tracked the chunk: chunk={}/{}, "
                                + "sections={}->{}, trackerReadyBefore=false",
                        chunkX, chunkZ, sectionsBefore, sectionsAfter);
            }
            if (FIRST_DIRECT_SECTION.compareAndSet(false, true)) {
                CruiseDebug.info(ImmersiveAircraftCruise.LOGGER,
                        "[CruiseRender] direct Embeddium section creation active at chunk={}/{}",
                        chunkX, chunkZ);
            }
        } catch (ReflectiveOperationException exception) {
            failures++;
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
            lightCalls++;
            TRACKER_STATUS_ADDED.invoke(tracker, chunkX, chunkZ, 2);
            CruiseDebug.debug(ImmersiveAircraftCruise.LOGGER,
                    "[CruiseRender] tracker status added: chunk={}/{}, trackerReadyAfter={}",
                    chunkX, chunkZ, isTrackerReady(chunkX, chunkZ));
        } catch (ReflectiveOperationException exception) {
            failures++;
            // Keep the route packet usable if a different optional renderer changes its tracker API.
        }
    }

    /** Emits and clears the per-second render bridge tally. */
    public static void logSummary() {
        if (visibleCalls == 0 && visibleUntracked == 0 && visibleUnavailable == 0
                && lightCalls == 0 && failures == 0) {
            return;
        }
        CruiseDebug.info(ImmersiveAircraftCruise.LOGGER,
                "[CruiseRender] visible={}, sectionsCreated={}, untrackedChunk={}, rendererUnavailable={}, "
                        + "light={}, failures={}",
                visibleCalls, visibleSectionsCreated, visibleUntracked, visibleUnavailable, lightCalls, failures);
        visibleCalls = 0;
        visibleSectionsCreated = 0;
        visibleUntracked = 0;
        visibleUnavailable = 0;
        lightCalls = 0;
        failures = 0;
    }

    /** True when Embeddium's own tracker already counts this chunk as ready. */
    private static boolean isTrackerReady(int chunkX, int chunkZ) {
        if (TRACKER_GET == null || TRACKER_READY_CHUNKS == null || READY_CHUNKS_CONTAINS == null) {
            return false;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return false;
        }
        try {
            Object tracker = TRACKER_GET.invoke(null, level);
            Object ready = TRACKER_READY_CHUNKS.invoke(tracker);
            return (Boolean) READY_CHUNKS_CONTAINS.invoke(ready, ChunkPos.asLong(chunkX, chunkZ));
        } catch (ReflectiveOperationException exception) {
            return false;
        }
    }

    private static int sectionCount(Object sectionManager) {
        if (RENDER_SECTION_MANAGER_SECTION_COUNT == null) {
            return -1;
        }
        try {
            return (Integer) RENDER_SECTION_MANAGER_SECTION_COUNT.invoke(sectionManager);
        } catch (ReflectiveOperationException exception) {
            return -1;
        }
    }
}
