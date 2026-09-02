package com.g1739.immersiveaircraftcruise.client;

import com.g1739.immersiveaircraftcruise.ImmersiveAircraftCruise;
import com.g1739.immersiveaircraftcruise.CruiseDebug;
import com.g1739.immersiveaircraftcruise.client.gui.CruiseScreen;
import com.g1739.immersiveaircraftcruise.cruise.CruiseController;
import com.g1739.immersiveaircraftcruise.cruise.CruiseFuelInfo;
import com.g1739.immersiveaircraftcruise.cruise.CruiseModuleData;
import com.g1739.immersiveaircraftcruise.cruise.CruiseRoute;
import com.g1739.immersiveaircraftcruise.cruise.CruiseVehicleAccess;
import com.g1739.immersiveaircraftcruise.mixin.ClientChunkCacheInvoker;
import com.g1739.immersiveaircraftcruise.network.SyncVehicleInventoryPacket;
import com.g1739.immersiveaircraftcruise.cruise.CruiseChunkPayloadCodec;
import com.g1739.immersiveaircraftcruise.network.CruiseRouteChunkPacket;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import immersive_aircraft.entity.InventoryVehicleEntity;
import immersive_aircraft.entity.VehicleEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.entity.Entity;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class ClientPacketHandlers {
    /** Two replacements per tick sustain the three-wide stream at the aircraft's top observed speed. */
    private static final int ROUTE_CHUNKS_PER_CLIENT_TICK = 2;
    /** Keep the route stream moving at the aircraft's maximum flight speed. */
    private static final int ROUTE_CLIENT_APPLY_INTERVAL_TICKS = 1;
    /** The route path reaches 50 chunks ahead and is three chunks wide. */
    private static final int ROUTE_CLIENT_CACHE_RADIUS = 52;
    /** Match the server in-flight window so a slow client cannot grow an unbounded queue. */
    private static final int MAX_PENDING_ROUTE_CHUNKS = 32;
    private static final Queue<QueuedCruiseChunk> PENDING_ROUTE_CHUNKS =
            new ArrayBlockingQueue<>(MAX_PENDING_ROUTE_CHUNKS);
    private static final AtomicInteger PENDING_ROUTE_COUNT = new AtomicInteger();
    private static final AtomicLong ROUTE_SESSION = new AtomicLong();
    private static int routeApplyTick;
    private static final ExecutorService DECODE_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "iacruise-route-packet-decode");
        thread.setDaemon(true);
        return thread;
    });

    private ClientPacketHandlers() {
    }

    public static void enqueueCruiseChunk(CruiseRouteChunkPacket packet) {
        long session = ROUTE_SESSION.get();
        if (PENDING_ROUTE_COUNT.incrementAndGet() > MAX_PENDING_ROUTE_CHUNKS) {
            PENDING_ROUTE_COUNT.decrementAndGet();
            CruiseDebug.debug(ImmersiveAircraftCruise.LOGGER,
                    "[CruiseChunks][Client] route packet queue full; leaving packet unacknowledged: "
                            + "chunk={}/{}, hash={}, queueLimit={}",
                    packet.x(), packet.z(), packet.hash(), MAX_PENDING_ROUTE_CHUNKS);
            return;
        }
        if (packet.compressedPayload().length == 0) {
            if (!PENDING_ROUTE_CHUNKS.offer(new QueuedCruiseChunk(packet, null, null, session))) {
                PENDING_ROUTE_COUNT.decrementAndGet();
            }
            return;
        }
        try {
            DECODE_EXECUTOR.execute(() -> decodeCruiseChunk(packet, session));
        } catch (RejectedExecutionException exception) {
            PENDING_ROUTE_COUNT.decrementAndGet();
            CruiseDebug.debug(ImmersiveAircraftCruise.LOGGER,
                    "[CruiseChunks][Client] route packet decode executor unavailable; packet deferred: "
                            + "chunk={}/{}, hash={}", packet.x(), packet.z(), packet.hash());
        }
    }

    private static void decodeCruiseChunk(CruiseRouteChunkPacket packet, long session) {
        ClientboundLevelChunkWithLightPacket decoded = null;
        RuntimeException failure = null;
        try {
            byte[] payload = CruiseChunkPayloadCodec.decompress(packet.compressedPayload());
            if (CruiseChunkPayloadCodec.hash(payload) != packet.hash()) {
                throw new IllegalArgumentException("Cruise chunk payload hash mismatch");
            }
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(payload));
            try {
                decoded = new ClientboundLevelChunkWithLightPacket(buffer);
            } finally {
                buffer.release();
            }
        } catch (RuntimeException exception) {
            failure = exception;
        }
        if (ROUTE_SESSION.get() != session
                || !PENDING_ROUTE_CHUNKS.offer(new QueuedCruiseChunk(packet, decoded, failure, session))) {
            PENDING_ROUTE_COUNT.decrementAndGet();
        }
    }

    public static void clearRouteSession() {
        ROUTE_SESSION.incrementAndGet();
        routeApplyTick = 0;
        while (PENDING_ROUTE_CHUNKS.poll() != null) {
            PENDING_ROUTE_COUNT.decrementAndGet();
        }
        setRouteCacheRadius(false);
        CruiseHud.clearFuelInfo();
        CruiseRouteCache.close();
    }

    public static void processQueuedCruiseChunks() {
        if (++routeApplyTick < ROUTE_CLIENT_APPLY_INTERVAL_TICKS) {
            return;
        }
        routeApplyTick = 0;
        for (int processed = 0; processed < ROUTE_CHUNKS_PER_CLIENT_TICK; processed++) {
            QueuedCruiseChunk queued = PENDING_ROUTE_CHUNKS.poll();
            if (queued == null) {
                return;
            }
            PENDING_ROUTE_COUNT.decrementAndGet();
            receiveCruiseChunk(queued);
        }
    }

    public static void openCruiseScreen(int entityId, CruiseRoute route, boolean readOnly) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        Entity entity = minecraft.level.getEntity(entityId);
        if (entityId < 0 || (entity instanceof VehicleEntity && entity instanceof CruiseVehicleAccess)) {
            minecraft.setScreen(new CruiseScreen(entityId, route.copy(), readOnly));
        }
    }

    public static void updateCruiseRoute(int entityId, CruiseRoute route) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        setRouteCacheRadius(route.isEnabled());
        Entity entity = minecraft.level.getEntity(entityId);
        if (entity instanceof VehicleEntity && entity instanceof CruiseVehicleAccess access) {
            CruiseRoute previous = access.iacruise$getRoute();
            CruiseRoute synced = route.copy();
            CruiseController.reconcileRouteDefinitionChange((VehicleEntity) entity, previous, synced);
            access.iacruise$setRoute(synced);
            if (!synced.isEnabled() || !synced.hasTarget()) {
                CruiseHud.invalidateFlightTime(entityId);
            }
            if (previous == null || previous.isEnabled() != synced.isEnabled()) {
                int prunedActiveChunks = synced.isEnabled() ? CruiseRouteCache.pruneInactive() : 0;
                CruiseDebug.info(ImmersiveAircraftCruise.LOGGER,
                        "[CruiseChunks][Client] route state sync applied: vehicleId={}, enabled={} -> {}, "
                                + "hasTarget={}, prunedInactiveChunks={}",
                        entityId, previous != null && previous.isEnabled(), synced.isEnabled(), synced.hasTarget(),
                        prunedActiveChunks);
            }
            if (!synced.isEnabled() && previous != null && previous.isEnabled()) {
                CruiseController.stopNavigationEffects((VehicleEntity) entity);
                CruiseController.clearCruiseInputs((VehicleEntity) entity);
            }
        } else {
            ImmersiveAircraftCruise.LOGGER.warn(
                    "[CruiseChunks][Client] route state sync ignored: vehicleId={}, entity={}",
                    entityId, entity == null ? "null" : entity.getClass().getSimpleName());
        }
        if (minecraft.screen instanceof CruiseScreen screen && screen.isForEntity(entityId)) {
            screen.updateRuntime(route);
        }
    }

    private static void setRouteCacheRadius(boolean routeEnabled) {
        if (!routeEnabled) {
            CruiseClientCacheView.clearRouteRadiusOverride();
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        int normalRadius = Math.max(2, minecraft.options.renderDistance().get());
        int targetRadius = routeEnabled ? ROUTE_CLIENT_CACHE_RADIUS : normalRadius;
        if (routeEnabled) {
            CruiseClientCacheView.setRouteRadiusOverride(targetRadius);
        }
        if (CruiseClientCacheView.radius() == targetRadius) {
            return;
        }
        ClientChunkCache chunkSource = minecraft.level.getChunkSource();
        ((ClientChunkCacheInvoker) chunkSource).iacruise$updateViewRadius(targetRadius);
        CruiseDebug.info(ImmersiveAircraftCruise.LOGGER,
                "[CruiseChunks][Client] route cache radius switched: enabled={}, radius={}",
                routeEnabled, targetRadius);
    }

    public static void updateCruiseFuel(int entityId, CruiseFuelInfo fuelInfo) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null) {
            Entity entity = minecraft.level.getEntity(entityId);
            if (entity instanceof VehicleEntity vehicle) {
                CruiseModuleData.setBoosting(vehicle, fuelInfo.boosting());
            }
        }
        CruiseHud.setFuelInfo(entityId, fuelInfo);
        CruiseDebug.debug(ImmersiveAircraftCruise.LOGGER,
                "[CruiseHud] fuel sync applied: entityId={}, remainingTicks={}, amount={}, speed={}, "
                        + "boosting={}, clientGameTime={}, thread={}",
                entityId, fuelInfo.remainingTicks(), fuelInfo.amountText(), fuelInfo.speed(),
                fuelInfo.boosting(), minecraft.level == null ? -1L : minecraft.level.getGameTime(),
                Thread.currentThread().getName());
    }

    public static void updateVehicleInventory(int entityId, List<SyncVehicleInventoryPacket.Entry> entries) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        Entity entity = minecraft.level.getEntity(entityId);
        if (!(entity instanceof InventoryVehicleEntity vehicle)) {
            return;
        }
        int size = vehicle.getInventory().getContainerSize();
        for (SyncVehicleInventoryPacket.Entry entry : entries) {
            if (entry.slot() >= 0 && entry.slot() < size) {
                vehicle.getInventory().setItem(entry.slot(), entry.stack().copy());
            }
        }
    }

    public static void receiveCruiseChunk(CruiseRouteChunkPacket packet) {
        receiveCruiseChunk(new QueuedCruiseChunk(packet, null, null, ROUTE_SESSION.get()));
    }

    private static void receiveCruiseChunk(QueuedCruiseChunk queued) {
        if (queued.session() != ROUTE_SESSION.get()) {
            return;
        }
        CruiseRouteChunkPacket packet = queued.packet();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !minecraft.level.dimension().location().toString().equals(packet.dimension())) {
            return;
        }
        CruiseRouteCache.ensureOpen(packet.namespace(), packet.dimension());
        long sequence = CruiseRouteCache.nextDiagnosticSequence();
        boolean recordedActiveBefore = CruiseRouteCache.isActive(packet.x(), packet.z(), packet.hash());
        LevelChunk liveChunkBefore = minecraft.level.getChunkSource().getChunk(
                packet.x(), packet.z(), ChunkStatus.FULL, false);
        boolean liveBefore = liveChunkBefore != null && !(liveChunkBefore instanceof EmptyLevelChunk);
        boolean activeBefore = recordedActiveBefore && liveBefore;
        if (recordedActiveBefore && !liveBefore) {
            CruiseRouteCache.deactivate(packet.x(), packet.z(), packet.hash());
            ImmersiveAircraftCruise.LOGGER.warn(
                    "[CruiseChunks][Client] stale active route chunk removed: seq={}, chunk={}/{}, hash={}, "
                            + "cacheCenter={}/{}, cacheRadius={}, loadedChunks={}",
                    sequence, packet.x(), packet.z(), packet.hash(),
                    CruiseClientCacheView.centerX(), CruiseClientCacheView.centerZ(),
                    CruiseClientCacheView.radius(), minecraft.level.getChunkSource().getLoadedChunksCount());
        }
        CruiseDebug.debug(ImmersiveAircraftCruise.LOGGER,
                "[CruiseChunks][Client] route packet begin: seq={}, chunk={}/{}, hash={}, "
                        + "payloadBytes={}, recordedActiveBefore={}, liveBefore={}, activeBefore={}, "
                        + "cacheCenter={}/{}, cacheRadius={}, loadedChunks={}",
                sequence, packet.x(), packet.z(), packet.hash(), packet.compressedPayload().length,
                recordedActiveBefore, liveBefore, activeBefore,
                CruiseClientCacheView.centerX(), CruiseClientCacheView.centerZ(),
                CruiseClientCacheView.radius(), minecraft.level.getChunkSource().getLoadedChunksCount());
        byte[] compressed = packet.compressedPayload().length == 0
                ? CruiseRouteCache.payload(packet.x(), packet.z(), packet.hash())
                : packet.compressedPayload();
        if (compressed == null) {
            ImmersiveAircraftCruise.LOGGER.warn(
                    "[CruiseChunks][Client] route packet cache miss: seq={}, x={}, z={}, hash={}",
                    sequence, packet.x(), packet.z(), packet.hash());
            CruiseRouteCache.reject(packet.x(), packet.z(), packet.hash());
            return;
        }
        CruiseDebug.debug(ImmersiveAircraftCruise.LOGGER,
                "[CruiseChunks][Client] route packet received: seq={}, x={}, z={}, hash={}, source={}, active={}",
                sequence, packet.x(), packet.z(), packet.hash(),
                packet.compressedPayload().length == 0 ? "disk" : "network",
                activeBefore);
        if (!activeBefore) {
            try {
                if (queued.decodeFailure() != null) {
                    throw queued.decodeFailure();
                }
                ClientboundLevelChunkWithLightPacket decoded = queued.decodedPacket();
                if (decoded == null) {
                    byte[] payload = CruiseChunkPayloadCodec.decompress(compressed);
                    if (CruiseChunkPayloadCodec.hash(payload) != packet.hash()) {
                        throw new IllegalArgumentException("Cruise chunk payload hash mismatch");
                    }
                    FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(payload));
                    try {
                        decoded = new ClientboundLevelChunkWithLightPacket(buffer);
                    } finally {
                        buffer.release();
                    }
                }
                decoded.handle(minecraft.getConnection());
            } catch (RuntimeException exception) {
                ImmersiveAircraftCruise.LOGGER.warn(
                        "[CruiseChunks][Client] route payload rejected: seq={}, chunk={}/{}, hash={}",
                        sequence, packet.x(), packet.z(), packet.hash(), exception);
                CruiseRouteCache.discard(packet.x(), packet.z(), packet.hash());
                CruiseRouteCache.reject(packet.x(), packet.z(), packet.hash());
                return;
            }
            LevelChunk chunk = minecraft.level.getChunkSource().getChunk(
                    packet.x(), packet.z(), ChunkStatus.FULL, false);
            if (chunk == null || chunk instanceof EmptyLevelChunk) {
                ImmersiveAircraftCruise.LOGGER.warn(
                        "[CruiseChunks][Client] route packet rejected by ClientChunkCache: x={}, z={}, hash={}, loadedChunks={}",
                        packet.x(), packet.z(), packet.hash(),
                        minecraft.level.getChunkSource().getLoadedChunksCount());
                CruiseRouteCache.reject(packet.x(), packet.z(), packet.hash());
                return;
            }
            EmbeddiumRenderBridge.markChunkVisible(packet.x(), packet.z());
            ClientLevel clientLevel = minecraft.level;
            clientLevel.queueLightUpdate(() -> EmbeddiumRenderBridge.markLightReady(
                    clientLevel, packet.x(), packet.z()));
            CruiseRouteCache.activate(packet.x(), packet.z(), packet.hash());
            if (packet.compressedPayload().length > 0) {
                CruiseRouteCache.save(packet.x(), packet.z(), packet.hash(), compressed);
            }
            CruiseDebug.debug(ImmersiveAircraftCruise.LOGGER,
                    "[CruiseChunks][Client] route packet applied: seq={}, x={}, z={}, hash={}, loadedChunks={}",
                    sequence, packet.x(), packet.z(), packet.hash(),
                    minecraft.level.getChunkSource().getLoadedChunksCount());
        } else {
            CruiseDebug.debug(ImmersiveAircraftCruise.LOGGER,
                    "[CruiseChunks][Client] route packet reused active chunk: seq={}, x={}, z={}, hash={}, loadedChunks={}",
                    sequence, packet.x(), packet.z(), packet.hash(),
                    minecraft.level.getChunkSource().getLoadedChunksCount());
        }
        CruiseRouteCache.acknowledge(packet.x(), packet.z(), packet.hash());
    }

    private record QueuedCruiseChunk(CruiseRouteChunkPacket packet,
                                     ClientboundLevelChunkWithLightPacket decodedPacket,
                                     RuntimeException decodeFailure,
                                     long session) {
    }

}
