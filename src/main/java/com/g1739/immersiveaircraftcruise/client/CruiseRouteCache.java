package com.g1739.immersiveaircraftcruise.client;

import com.g1739.immersiveaircraftcruise.ImmersiveAircraftCruise;
import com.g1739.immersiveaircraftcruise.CruiseDebug;
import com.g1739.immersiveaircraftcruise.network.CruiseChunkStatePacket;
import com.g1739.immersiveaircraftcruise.network.CruiseNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class CruiseRouteCache {
    private static String namespace;
    private static String dimension;
    private static Path directory;
    private static final int MAX_ENTRIES = 1024;
    private static final long MAX_PAYLOAD_BYTES = 4L * 1024L * 1024L;
    private static final Map<Long, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private static final Map<Long, Long> active = new HashMap<>();
    private static final AtomicLong DIAGNOSTIC_SEQUENCE = new AtomicLong();
    private static final AtomicLong SESSION_GENERATION = new AtomicLong();
    private static final ExecutorService IO_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "iacruise-route-cache-io");
        thread.setDaemon(true);
        return thread;
    });

    private CruiseRouteCache() {
    }

    public static void open(String cacheNamespace, String cacheDimension) {
        long generation = SESSION_GENERATION.incrementAndGet();
        namespace = cacheNamespace;
        dimension = cacheDimension;
        directory = Minecraft.getInstance().gameDirectory.toPath()
                .resolve("immersive_aircraft_cruise")
                .resolve("route-cache")
                .resolve(cacheNamespace)
                .resolve(Integer.toHexString(cacheDimension.hashCode()));
        entries.clear();
        active.clear();
        DIAGNOSTIC_SEQUENCE.set(0L);
        Path cacheDirectory = directory;
        try {
            Files.createDirectories(cacheDirectory);
            IO_EXECUTOR.execute(() -> loadEntries(cacheDirectory, generation));
        } catch (IOException | SecurityException exception) {
            ImmersiveAircraftCruise.LOGGER.warn(
                    "[CruiseChunks][Client] route cache unavailable; continuing with memory-only cache: {}",
                    cacheDirectory, exception);
        }
    }

    private static void loadEntries(Path cacheDirectory, long generation) {
        Map<Long, Entry> loadedEntries = new LinkedHashMap<>(16, 0.75f, true);
        try (var stream = Files.list(cacheDirectory)) {
            stream.filter(path -> path.getFileName().toString().endsWith(".bin"))
                    .forEach(path -> readEntry(path, loadedEntries));
        } catch (IOException | SecurityException exception) {
            ImmersiveAircraftCruise.LOGGER.warn(
                    "[CruiseChunks][Client] unable to load route cache entries; continuing with memory-only cache: {}",
                    cacheDirectory, exception);
        }
        try {
            Minecraft.getInstance().execute(() -> mergeLoadedEntries(cacheDirectory, generation, loadedEntries));
        } catch (RuntimeException exception) {
            // The client executor may already be stopping during world unload; the cache is optional then.
            CruiseDebug.debug(ImmersiveAircraftCruise.LOGGER,
                    "[CruiseChunks][Client] route cache load result dropped during client shutdown: {}",
                    cacheDirectory);
        }
    }

    private static void readEntry(Path path, Map<Long, Entry> loadedEntries) {
        String name = path.getFileName().toString();
        String[] coordinates = name.substring(0, name.length() - 4).split("_");
        if (coordinates.length != 2) {
            return;
        }
        try {
            if (Files.size(path) > Long.BYTES + MAX_PAYLOAD_BYTES) {
                ImmersiveAircraftCruise.LOGGER.warn(
                        "[CruiseChunks][Client] oversized route cache entry skipped: {}", path);
                return;
            }
        } catch (IOException | SecurityException exception) {
            ImmersiveAircraftCruise.LOGGER.warn(
                    "[CruiseChunks][Client] unable to inspect route cache entry {}; skipping", path, exception);
            return;
        }
        try (DataInputStream input = new DataInputStream(Files.newInputStream(path))) {
            long hash = input.readLong();
            byte[] payload = input.readAllBytes();
            int x = Integer.parseInt(coordinates[0]);
            int z = Integer.parseInt(coordinates[1]);
            loadedEntries.put((((long) x) << 32) ^ (z & 0xffffffffL), new Entry(hash, payload));
        } catch (IOException | NumberFormatException exception) {
            ImmersiveAircraftCruise.LOGGER.warn("[CruiseChunks][Client] invalid cache entry {}", path, exception);
        }
    }

    private static void mergeLoadedEntries(Path cacheDirectory, long generation, Map<Long, Entry> loadedEntries) {
        if (SESSION_GENERATION.get() != generation || !Objects.equals(directory, cacheDirectory)) {
            return;
        }
        for (Map.Entry<Long, Entry> loaded : loadedEntries.entrySet()) {
            entries.putIfAbsent(loaded.getKey(), loaded.getValue());
        }
        trimEntriesInMemory();
    }

    public static byte[] payload(int x, int z, long hash) {
        Entry entry = entries.get(key(x, z));
        return entry != null && entry.hash() == hash ? entry.payload() : null;
    }

    public static void save(int x, int z, long hash, byte[] payload) {
        if (payload == null || payload.length > MAX_PAYLOAD_BYTES) {
            ImmersiveAircraftCruise.LOGGER.warn(
                    "[CruiseChunks][Client] invalid or oversized route cache payload ignored: chunk={}/{}, bytes={}",
                    x, z, payload == null ? -1 : payload.length);
            return;
        }
        byte[] memoryPayload = payload.clone();
        entries.put(key(x, z), new Entry(hash, memoryPayload));
        trimEntriesInMemory();
        Path cacheDirectory = directory;
        if (cacheDirectory == null) {
            return;
        }
        long generation = SESSION_GENERATION.get();
        Path path = cacheDirectory.resolve(x + "_" + z + ".bin");
        IO_EXECUTOR.execute(() -> writeEntry(cacheDirectory, generation, path, hash, memoryPayload));
    }

    private static void writeEntry(Path cacheDirectory, long generation, Path path, long hash, byte[] payload) {
        if (SESSION_GENERATION.get() != generation || !Objects.equals(directory, cacheDirectory)) {
            return;
        }
        try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(path))) {
            output.writeLong(hash);
            output.write(payload);
        } catch (IOException | SecurityException exception) {
            ImmersiveAircraftCruise.LOGGER.warn(
                    "[CruiseChunks][Client] unable to persist route cache entry; keeping memory copy: {}", path,
                    exception);
        }
    }

    public static void discard(int x, int z, long hash) {
        long chunkKey = key(x, z);
        Entry entry = entries.get(chunkKey);
        if (entry == null || entry.hash() != hash) {
            return;
        }
        entries.remove(chunkKey);
        Path cacheDirectory = directory;
        if (cacheDirectory != null) {
            long generation = SESSION_GENERATION.get();
            Path path = cacheDirectory.resolve(x + "_" + z + ".bin");
            IO_EXECUTOR.execute(() -> deleteEntry(cacheDirectory, generation, path, x, z));
        }
    }

    private static void deleteEntry(Path cacheDirectory, long generation, Path path, int x, int z) {
        if (SESSION_GENERATION.get() != generation || !Objects.equals(directory, cacheDirectory)) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException | SecurityException exception) {
            ImmersiveAircraftCruise.LOGGER.warn(
                    "[CruiseChunks][Client] unable to discard cache entry {}/{}", x, z, exception);
        }
    }

    public static void markActive(int x, int z, long hash) {
        active.put(key(x, z), hash);
    }

    public static boolean isActive(int x, int z, long hash) {
        return active.getOrDefault(key(x, z), Long.MIN_VALUE) == hash;
    }

    public static boolean deactivate(int x, int z, long hash) {
        return active.remove(key(x, z), hash);
    }

    public static int pruneInactive() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            int removed = active.size();
            active.clear();
            return removed;
        }
        int before = active.size();
        active.entrySet().removeIf(entry -> {
            int x = (int) (entry.getKey() >> 32);
            int z = (int) (long) entry.getKey();
            LevelChunk chunk = minecraft.level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
            return chunk == null || chunk instanceof EmptyLevelChunk;
        });
        return before - active.size();
    }

    public static long activeHash(int x, int z) {
        return active.getOrDefault(key(x, z), Long.MIN_VALUE);
    }

    public static long nextDiagnosticSequence() {
        return DIAGNOSTIC_SEQUENCE.incrementAndGet();
    }

    public static void evict(int x, int z) {
        long key = key(x, z);
        Long hash = active.remove(key);
        if (hash != null && namespace != null) {
            CruiseDebug.debug(ImmersiveAircraftCruise.LOGGER,
                    "[CruiseChunks][Client] route state EVICTED: chunk={}/{}, hash={}, "
                            + "cacheCenter={}/{}, cacheRadius={}, loadedChunks={}",
                    x, z, hash, CruiseClientCacheView.centerX(), CruiseClientCacheView.centerZ(),
                    CruiseClientCacheView.radius(), loadedChunks());
            CruiseNetwork.sendToServer(new CruiseChunkStatePacket(
                    x, z, hash, CruiseChunkStatePacket.State.EVICTED));
        }
    }

    public static void activate(int x, int z, long hash) {
        active.put(key(x, z), hash);
    }

    public static void acknowledge(int x, int z, long hash) {
        CruiseDebug.debug(ImmersiveAircraftCruise.LOGGER,
                "[CruiseChunks][Client] route state ACTIVE: chunk={}/{}, hash={}, activeHash={}, "
                        + "cacheCenter={}/{}, cacheRadius={}, loadedChunks={}",
                x, z, hash, activeHash(x, z), CruiseClientCacheView.centerX(), CruiseClientCacheView.centerZ(),
                CruiseClientCacheView.radius(), loadedChunks());
        CruiseNetwork.sendToServer(new CruiseChunkStatePacket(
                x, z, hash, CruiseChunkStatePacket.State.ACTIVE));
    }

    public static void reject(int x, int z, long hash) {
        ImmersiveAircraftCruise.LOGGER.warn(
                "[CruiseChunks][Client] route state MISSING: chunk={}/{}, hash={}, activeHash={}, "
                        + "cacheCenter={}/{}, cacheRadius={}, loadedChunks={}",
                x, z, hash, activeHash(x, z), CruiseClientCacheView.centerX(), CruiseClientCacheView.centerZ(),
                CruiseClientCacheView.radius(), loadedChunks());
        CruiseNetwork.sendToServer(new CruiseChunkStatePacket(
                x, z, hash, CruiseChunkStatePacket.State.MISSING));
    }

    private static int loadedChunks() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level == null ? -1 : minecraft.level.getChunkSource().getLoadedChunksCount();
    }

    public static void ensureOpen(String cacheNamespace, String cacheDimension) {
        if (!Objects.equals(cacheNamespace, namespace) || !Objects.equals(cacheDimension, dimension)) {
            open(cacheNamespace, cacheDimension);
        }
    }

    public static void close() {
        SESSION_GENERATION.incrementAndGet();
        namespace = null;
        dimension = null;
        directory = null;
        entries.clear();
        active.clear();
    }

    private static long key(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }

    private static void trimEntriesInMemory() {
        while (entries.size() > MAX_ENTRIES) {
            Iterator<Map.Entry<Long, Entry>> iterator = entries.entrySet().iterator();
            Map.Entry<Long, Entry> eldest = iterator.next();
            iterator.remove();
            Path cacheDirectory = directory;
            if (cacheDirectory == null) {
                continue;
            }
            int x = (int) (eldest.getKey() >> 32);
            int z = (int) (long) eldest.getKey();
            long generation = SESSION_GENERATION.get();
            Path path = cacheDirectory.resolve(x + "_" + z + ".bin");
            IO_EXECUTOR.execute(() -> deleteEntry(cacheDirectory, generation, path, x, z));
        }
    }

    private record Entry(long hash, byte[] payload) {
    }

}
