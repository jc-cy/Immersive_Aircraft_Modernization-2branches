package com.g1739.immersiveaircraftcruise.cruise;

import net.minecraft.server.level.ServerLevel;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Server-side route chunk payloads, kept for the running session only.
 *
 * <p>The cache exists to spare the server a fresh serialisation and compression per viewer and to keep
 * the client snapshot stable while a chunk stays in the corridor. It is deliberately not persisted: a
 * snapshot is only trustworthy while the session that built it is the one serving it. Writing it to disk
 * made the server keep serving chunks the world no longer contained - seasonal TFC blocks removed after
 * the snapshot was taken never reached the block-change hook - which showed up in game as blocks that
 * vanished on relog.</p>
 */
public final class CruiseChunkPayloadCache {
    private static final int MAX_SNAPSHOTS = 1024;
    private static final Map<ServerLevel, CruiseChunkPayloadCache> LEVELS = new HashMap<>();

    private final String namespace;
    private final Map<Long, Snapshot> snapshots = new LinkedHashMap<>(16, 0.75f, true);

    private CruiseChunkPayloadCache(String namespace) {
        this.namespace = namespace;
    }

    public static CruiseChunkPayloadCache get(ServerLevel level) {
        return LEVELS.computeIfAbsent(level, key -> new CruiseChunkPayloadCache(namespaceFor(key)));
    }

    /** Every session rebuilds its payloads from the live chunk, so nothing carries across a restart. */
    public static void clearAll() {
        LEVELS.clear();
    }

    /** Stable per world, so the client keeps one route cache directory instead of one per session. */
    private static String namespaceFor(ServerLevel level) {
        String key = level.getServer().getWorldData().getLevelName() + '|' + level.dimension().location();
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
    }

    public String namespace() {
        return namespace;
    }

    public Snapshot get(long chunkKey) {
        return snapshots.get(chunkKey);
    }

    public void put(long chunkKey, Snapshot snapshot) {
        snapshots.put(chunkKey, snapshot);
        trim();
    }

    public void invalidate(long chunkKey) {
        snapshots.remove(chunkKey);
    }

    public record Snapshot(long hash, byte[] compressedPayload) {
    }

    private void trim() {
        while (snapshots.size() > MAX_SNAPSHOTS) {
            Iterator<Long> iterator = snapshots.keySet().iterator();
            iterator.next();
            iterator.remove();
        }
    }
}
