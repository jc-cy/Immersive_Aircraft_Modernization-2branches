package com.g1739.immersiveaircraftcruise.cruise;


import com.g1739.immersiveaircraftcruise.ImmersiveAircraftCruise;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class CruiseChunkPayloadCache extends SavedData {
    private static final String DATA_NAME = "immersive_aircraft_cruise_route_cache";
    private static final int MAX_SNAPSHOTS = 1024;
    private static final int MAX_COMPRESSED_PAYLOAD = 4 * 1024 * 1024;
    private static final String NAMESPACE_KEY = "Namespace";
    private static final String CHUNKS_KEY = "Chunks";

    private final String namespace;
    private final Map<Long, Snapshot> snapshots;

    private CruiseChunkPayloadCache(String namespace, Map<Long, Snapshot> snapshots) {
        this.namespace = namespace;
        this.snapshots = snapshots;
    }

    public static CruiseChunkPayloadCache get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(() -> new CruiseChunkPayloadCache(
                        UUID.randomUUID().toString().replace("-", ""),
                        new LinkedHashMap<>(16, 0.75f, true)), CruiseChunkPayloadCache::load),
                DATA_NAME);
    }

    private static CruiseChunkPayloadCache load(CompoundTag tag, HolderLookup.Provider registries) {
        Map<Long, Snapshot> snapshots = new LinkedHashMap<>(16, 0.75f, true);
        CompoundTag chunks = tag.getCompound(CHUNKS_KEY);
        for (String key : chunks.getAllKeys()) {
            try {
                CompoundTag entry = chunks.getCompound(key);
                byte[] payload = entry.getByteArray("Payload");
                if (payload.length > MAX_COMPRESSED_PAYLOAD) {
                    ImmersiveAircraftCruise.LOGGER.warn(
                            "[CruiseChunks][Server] oversized saved route payload skipped: key={}, bytes={}",
                            key, payload.length);
                    continue;
                }
                snapshots.put(Long.parseLong(key), new Snapshot(entry.getLong("Hash"), payload));
            } catch (NumberFormatException exception) {
                // A damaged SavedData entry must not prevent the server from starting.
                ImmersiveAircraftCruise.LOGGER.warn(
                        "[CruiseChunks][Server] invalid saved route cache key skipped: {}", key, exception);
            }
        }
        String namespace = tag.getString(NAMESPACE_KEY);
        if (namespace.isEmpty()) {
            namespace = UUID.randomUUID().toString().replace("-", "");
        }
        trim(snapshots);
        return new CruiseChunkPayloadCache(namespace, snapshots);
    }

    public String namespace() {
        return namespace;
    }

    public Snapshot get(long chunkKey) {
        return snapshots.get(chunkKey);
    }

    public void put(long chunkKey, Snapshot snapshot) {
        Snapshot previous = snapshots.put(chunkKey, snapshot);
        if (previous == null || previous.hash() != snapshot.hash()) {
            setDirty();
        }
        trim(snapshots);
    }

    public void invalidate(long chunkKey) {
        if (snapshots.remove(chunkKey) != null) {
            setDirty();
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putString(NAMESPACE_KEY, namespace);
        CompoundTag chunks = new CompoundTag();
        for (Map.Entry<Long, Snapshot> entry : snapshots.entrySet()) {
            CompoundTag snapshot = new CompoundTag();
            snapshot.putLong("Hash", entry.getValue().hash());
            snapshot.putByteArray("Payload", entry.getValue().compressedPayload());
            chunks.put(Long.toString(entry.getKey()), snapshot);
        }
        tag.put(CHUNKS_KEY, chunks);
        return tag;
    }

    public record Snapshot(long hash, byte[] compressedPayload) {
    }

    private static void trim(Map<Long, Snapshot> snapshots) {
        while (snapshots.size() > MAX_SNAPSHOTS) {
            Iterator<Long> iterator = snapshots.keySet().iterator();
            iterator.next();
            iterator.remove();
        }
    }
}
