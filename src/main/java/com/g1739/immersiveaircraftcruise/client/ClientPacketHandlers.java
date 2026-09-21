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
import com.g1739.immersiveaircraftcruise.network.CruiseNetwork;
import com.g1739.immersiveaircraftcruise.network.RequestCruiseRideResyncPacket;
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
import java.util.LinkedHashMap;
import java.util.Map;
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
    /** Entity spawn and route-state packets may cross; retain only a bounded, short-lived latest state. */
    private static final int MAX_PENDING_ROUTE_STATES = 32;
    private static final long PENDING_ROUTE_STATE_TTL_TICKS = 200L;
    private static final Queue<QueuedCruiseChunk> PENDING_ROUTE_CHUNKS =
            new ArrayBlockingQueue<>(MAX_PENDING_ROUTE_CHUNKS);
    private static final AtomicInteger PENDING_ROUTE_COUNT = new AtomicInteger();
    private static final AtomicLong ROUTE_SESSION = new AtomicLong();
    private static final Map<Integer, PendingRouteState> PENDING_ROUTE_STATES = new LinkedHashMap<>();
    private static final Map<Integer, PendingAccelerationPermit> PENDING_ACCELERATION_PERMITS = new LinkedHashMap<>();
    private static final Map<Integer, PendingVehicleInventory> PENDING_VEHICLE_INVENTORIES = new LinkedHashMap<>();
    private static int routeApplyTick;
    /**
     * The server's 5-tick fuel heartbeat already names the aircraft it considers us onboard. These
     * counters compare that with our own riding state; the server decides what to do with a mismatch.
     */
    private static final int RIDE_MISMATCH_STRIKES = 3;
    private static final long RIDE_RESYNC_CLIENT_COOLDOWN_TICKS = 20L;
    /**
     * Latency alone leaves a rider's copy behind by (cruise speed x round trip), which is already tens
     * of blocks on a 0.6s link, so one heartbeat cannot tell lag from a frozen copy. Only a gap that a
     * cruise aircraft cannot produce by lagging (150 blocks is about 1.3s of flight) counts, and it has
     * to hold for a full three seconds before the server is asked to correct anything.
     */
    private static final double RIDE_COPY_MAX_DRIFT_BLOCKS = 150.0d;
    private static final long RIDE_DRIFT_WINDOW_TICKS = 60L;
    /** Below this the authoritative copy did not advance either: the server is the one that is behind. */
    private static final double RIDE_SERVER_MOVED_MIN_BLOCKS = 1.0d;
    private static int heartbeatVehicleId = -1;
    private static int heartbeatStrikes;
    private static long driftWindowStartTick = Long.MIN_VALUE;
    private static double driftWindowServerX;
    private static double driftWindowServerY;
    private static double driftWindowServerZ;
    private static long lastRideResyncTick = Long.MIN_VALUE;
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
        invalidateRouteStream();
        PENDING_ROUTE_STATES.clear();
        PENDING_ACCELERATION_PERMITS.clear();
        PENDING_VEHICLE_INVENTORIES.clear();
        setRouteCacheRadius(false);
        CruiseHud.clearFuelInfo();
        CruiseRouteCache.close();
    }

    /**
     * Invalidates packets already in flight when navigation stops. The decode
     * worker may finish after this method returns, so the session check in
     * {@link #decodeCruiseChunk(CruiseRouteChunkPacket, long)} remains the
     * authority for those late results.
     */
    public static void invalidateRouteStream() {
        long session = ROUTE_SESSION.incrementAndGet();
        routeApplyTick = 0;
        int discarded = 0;
        while (PENDING_ROUTE_CHUNKS.poll() != null) {
            PENDING_ROUTE_COUNT.decrementAndGet();
            discarded++;
        }
        CruiseDebug.debug(ImmersiveAircraftCruise.LOGGER,
                "[CruiseChunks][Client] route stream invalidated: session={}, discardedQueuedPackets={}",
                session, discarded);
    }

    public static void processQueuedCruiseChunks() {
        processPendingRouteStates();
        processPendingAccelerationPermits();
        processPendingVehicleInventories();
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

    public static void openCruiseScreen(int entityId, CruiseRoute route, boolean readOnly,
                                        boolean decelerateWhenChunksNotReady) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        // Apply stream/session state immediately even when the entity spawn packet
        // is still in flight; otherwise a disabled route could leave stale chunks
        // active until the vehicle eventually appears.
        if (!route.isEnabled()) {
            invalidateRouteStream();
        }
        Entity entity = minecraft.level.getEntity(entityId);
        if (entityId < 0 || (entity instanceof VehicleEntity && entity instanceof CruiseVehicleAccess)) {
            minecraft.setScreen(new CruiseScreen(entityId, route.copy(), readOnly,
                    decelerateWhenChunksNotReady));
        }
    }

    public static void updateCruiseRoute(int entityId, CruiseRoute route) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        Entity entity = minecraft.level.getEntity(entityId);
        if (!(entity instanceof VehicleEntity) || !(entity instanceof CruiseVehicleAccess)) {
            if (entity == null) {
                if (PENDING_ROUTE_STATES.size() >= MAX_PENDING_ROUTE_STATES
                        && !PENDING_ROUTE_STATES.containsKey(entityId)) {
                    Integer eldest = PENDING_ROUTE_STATES.keySet().iterator().next();
                    PENDING_ROUTE_STATES.remove(eldest);
                }
                PENDING_ROUTE_STATES.put(entityId,
                        new PendingRouteState(route.copy(), minecraft.level.getGameTime()));
                CruiseDebug.debug(ImmersiveAircraftCruise.LOGGER,
                        "[CruiseChunks][Client] queued route state until entity spawn: vehicleId={}, pending={}",
                        entityId, PENDING_ROUTE_STATES.size());
            } else {
                ImmersiveAircraftCruise.LOGGER.warn(
                        "[CruiseChunks][Client] route state sync ignored: vehicleId={}, entity={}",
                        entityId, entity.getClass().getSimpleName());
            }
            return;
        }
        applyCruiseRoute(minecraft, entityId, route, entity);
    }

    private static void processPendingRouteStates() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || PENDING_ROUTE_STATES.isEmpty()) {
            return;
        }
        long now = minecraft.level.getGameTime();
        java.util.Iterator<Map.Entry<Integer, PendingRouteState>> iterator =
                PENDING_ROUTE_STATES.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, PendingRouteState> entry = iterator.next();
            Entity entity = minecraft.level.getEntity(entry.getKey());
            if (entity instanceof VehicleEntity && entity instanceof CruiseVehicleAccess) {
                PendingRouteState pending = entry.getValue();
                iterator.remove();
                applyCruiseRoute(minecraft, entry.getKey(), pending.route(), entity);
            } else if (now - entry.getValue().queuedTick() >= PENDING_ROUTE_STATE_TTL_TICKS) {
                iterator.remove();
                CruiseDebug.debug(ImmersiveAircraftCruise.LOGGER,
                        "[CruiseChunks][Client] expired pending route state: vehicleId={}, ageTicks={}",
                        entry.getKey(), now - entry.getValue().queuedTick());
            }
        }
    }

    private static void applyCruiseRoute(Minecraft minecraft, int entityId, CruiseRoute route, Entity entity) {
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
        }
        if (minecraft.screen instanceof CruiseScreen screen && screen.isForEntity(entityId)) {
            screen.updateRuntime(route);
        }
    }

    private record PendingRouteState(CruiseRoute route, long queuedTick) {
    }

    private record PendingAccelerationPermit(boolean permitted, long queuedTick) {
    }

    private record PendingVehicleInventory(List<SyncVehicleInventoryPacket.Entry> entries, long queuedTick) {
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

    /**
     * Keeps the widened cache bound to the ride state itself: it is on exactly while this client is on
     * an aircraft whose route is running. Reconciling the state instead of reacting to events means no
     * dismount path (key, teleport, dimension change, death, chunk unload) can leave it behind, and no
     * other aircraft's route state can switch it off.
     */
    public static void reconcileRouteCacheRadius() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        Entity root = minecraft.player.getRootVehicle();
        boolean ridingRoute = root instanceof VehicleEntity vehicle
                && vehicle instanceof CruiseVehicleAccess access
                && access.iacruise$getRoute().isEnabled();
        setRouteCacheRadius(ridingRoute);
    }

    public static void updateCruiseFuel(int entityId, double x, double y, double z, CruiseFuelInfo fuelInfo) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null) {
            Entity entity = minecraft.level.getEntity(entityId);
            if (entity instanceof VehicleEntity vehicle) {
                CruiseModuleData.setBoosting(vehicle, fuelInfo.boosting());
            }
        }
        CruiseHud.setFuelInfo(entityId, fuelInfo);
        watchRideHeartbeat(entityId, x, y, z);
        Entity entity = minecraft.level == null ? null : minecraft.level.getEntity(entityId);
        String recipientRole = entity instanceof VehicleEntity vehicle
                && minecraft.player != null
                && vehicle.getControllingPassenger() == minecraft.player
                ? "pilot" : "passenger";
        CruiseDebug.info(ImmersiveAircraftCruise.LOGGER,
                "[CruiseFuelSync] received: clientGameTime={}, entityId={}, role={}, amount={}, "
                        + "remainingTicks={}, speed={}, boosting={}, thread={}",
                minecraft.level == null ? -1L : minecraft.level.getGameTime(), entityId, recipientRole,
                fuelInfo.amountText(), fuelInfo.remainingTicks(), fuelInfo.speed(), fuelInfo.boosting(),
                Thread.currentThread().getName());
    }

    /**
     * Compares the heartbeat's aircraft with our own view of it; the server repairs whatever disagrees.
     *
     * <p>Two anomalies exist. Either we do not ride the aircraft the server counts us on, or we ride it
     * but our copy of it is stale - the aircraft stays under the player while the server flies on, and
     * no amount of ordinary state broadcast helps because our own copy is what is wrong. The heartbeat
     * carries the authoritative position, so a sustained distance between the two detects exactly that
     * second case, which is otherwise only recoverable by pressing the refresh key.
     *
     * <p>Lagging is not that case: latency produces a roughly constant offset, so the gap has to stay
     * beyond {@link #RIDE_COPY_MAX_DRIFT_BLOCKS} for a whole {@link #RIDE_DRIFT_WINDOW_TICKS} window.
     * If the authoritative position did not advance during that window either, the server itself is the
     * one that fell behind (a stall while preloading) and correcting this client would drag it back to
     * a stale position, so that case is only reported.
     */
    private static void watchRideHeartbeat(int entityId, double x, double y, double z) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        Entity root = minecraft.player.getRootVehicle();
        if (root instanceof VehicleEntity vehicle && vehicle.getId() == entityId) {
            heartbeatVehicleId = entityId;
            if (vehicle.position().distanceToSqr(x, y, z)
                    <= RIDE_COPY_MAX_DRIFT_BLOCKS * RIDE_COPY_MAX_DRIFT_BLOCKS) {
                heartbeatStrikes = 0;
                clearDriftWindow();
                return;
            }
            long gameTime = minecraft.level.getGameTime();
            if (driftWindowStartTick == Long.MIN_VALUE) {
                driftWindowStartTick = gameTime;
                driftWindowServerX = x;
                driftWindowServerY = y;
                driftWindowServerZ = z;
                return;
            }
            if (gameTime - driftWindowStartTick < RIDE_DRIFT_WINDOW_TICKS) {
                return;
            }
            double serverMoved = Math.abs(x - driftWindowServerX)
                    + Math.abs(y - driftWindowServerY)
                    + Math.abs(z - driftWindowServerZ);
            double drift = Math.sqrt(vehicle.position().distanceToSqr(x, y, z));
            clearDriftWindow();
            if (serverMoved < RIDE_SERVER_MOVED_MIN_BLOCKS) {
                ImmersiveAircraftCruise.LOGGER.warn(
                        "[CruiseRide][Client] aircraft copy drifted {} blocks for {} ticks, but the "
                                + "authoritative copy did not advance either; not repairing",
                        String.format("%.1f", drift), RIDE_DRIFT_WINDOW_TICKS);
                return;
            }
            requestRideResync("aircraft copy drifted " + String.format("%.1f", drift)
                    + " blocks for " + (RIDE_DRIFT_WINDOW_TICKS / 20L) + "s", vehicle);
            return;
        }
        clearDriftWindow();
        if (heartbeatVehicleId != entityId) {
            heartbeatVehicleId = entityId;
            heartbeatStrikes = 0;
        }
        if (++heartbeatStrikes >= RIDE_MISMATCH_STRIKES) {
            heartbeatStrikes = 0;
            requestRideResync("heartbeat names another aircraft", root);
        }
    }

    private static void clearDriftWindow() {
        driftWindowStartTick = Long.MIN_VALUE;
    }

    /**
     * Manual repair on its own key binding: the server repeats its own state for the aircraft this
     * client is riding, which rebuilds this client's copy of it.
     */
    public static void requestRideResync() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        Entity root = minecraft.player.getRootVehicle();
        if (!(root instanceof VehicleEntity vehicle) || !CruiseModuleData.hasModule(vehicle)) {
            return;
        }
        requestRideResync("manual", root);
    }

    private static void requestRideResync(String reason, Entity root) {
        Minecraft minecraft = Minecraft.getInstance();
        long gameTime = minecraft.level == null ? 0L : minecraft.level.getGameTime();
        if (lastRideResyncTick != Long.MIN_VALUE
                && gameTime - lastRideResyncTick < RIDE_RESYNC_CLIENT_COOLDOWN_TICKS) {
            return;
        }
        lastRideResyncTick = gameTime;
        int rootId = root instanceof VehicleEntity vehicle ? vehicle.getId() : 0;
        ImmersiveAircraftCruise.LOGGER.warn(
                "[CruiseRide][Client] riding-state validation ({}), root={}",
                reason, root == null ? "none" : root.getId());
        CruiseNetwork.CHANNEL.sendToServer(new RequestCruiseRideResyncPacket(rootId));
    }

    public static void updateCruiseAccelerationPermit(int entityId, boolean permitted) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        Entity entity = minecraft.level.getEntity(entityId);
        if (entity instanceof VehicleEntity vehicle) {
            CruiseController.updateClientAccelerationPermit(vehicle, permitted);
        } else if (entity == null) {
            if (PENDING_ACCELERATION_PERMITS.size() >= MAX_PENDING_ROUTE_STATES
                    && !PENDING_ACCELERATION_PERMITS.containsKey(entityId)) {
                Integer eldest = PENDING_ACCELERATION_PERMITS.keySet().iterator().next();
                PENDING_ACCELERATION_PERMITS.remove(eldest);
            }
            PENDING_ACCELERATION_PERMITS.put(entityId,
                    new PendingAccelerationPermit(permitted, minecraft.level.getGameTime()));
        }
    }

    private static void processPendingAccelerationPermits() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || PENDING_ACCELERATION_PERMITS.isEmpty()) {
            return;
        }
        long now = minecraft.level.getGameTime();
        java.util.Iterator<Map.Entry<Integer, PendingAccelerationPermit>> iterator =
                PENDING_ACCELERATION_PERMITS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, PendingAccelerationPermit> entry = iterator.next();
            Entity entity = minecraft.level.getEntity(entry.getKey());
            if (entity instanceof VehicleEntity vehicle) {
                iterator.remove();
                CruiseController.updateClientAccelerationPermit(vehicle, entry.getValue().permitted());
            } else if (now - entry.getValue().queuedTick() >= PENDING_ROUTE_STATE_TTL_TICKS) {
                iterator.remove();
            }
        }
    }

    public static void updateVehicleInventory(int entityId, List<SyncVehicleInventoryPacket.Entry> entries) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        Entity entity = minecraft.level.getEntity(entityId);
        if (entity instanceof InventoryVehicleEntity vehicle) {
            applyVehicleInventory(vehicle, entries);
            CruiseDebug.debug(ImmersiveAircraftCruise.LOGGER,
                    "[CruiseChunks][Client] vehicle inventory sync applied: vehicleId={}, entries={}",
                    entityId, entries.size());
            return;
        }
        if (entity == null) {
            if (PENDING_VEHICLE_INVENTORIES.size() >= MAX_PENDING_ROUTE_STATES
                    && !PENDING_VEHICLE_INVENTORIES.containsKey(entityId)) {
                Integer eldest = PENDING_VEHICLE_INVENTORIES.keySet().iterator().next();
                PENDING_VEHICLE_INVENTORIES.remove(eldest);
            }
            PENDING_VEHICLE_INVENTORIES.put(entityId,
                    new PendingVehicleInventory(List.copyOf(entries), minecraft.level.getGameTime()));
            CruiseDebug.debug(ImmersiveAircraftCruise.LOGGER,
                    "[CruiseChunks][Client] queued vehicle inventory until entity spawn: vehicleId={}, entries={}, pending={}",
                    entityId, entries.size(), PENDING_VEHICLE_INVENTORIES.size());
        }
    }

    private static void processPendingVehicleInventories() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || PENDING_VEHICLE_INVENTORIES.isEmpty()) {
            return;
        }
        long now = minecraft.level.getGameTime();
        java.util.Iterator<Map.Entry<Integer, PendingVehicleInventory>> iterator =
                PENDING_VEHICLE_INVENTORIES.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, PendingVehicleInventory> entry = iterator.next();
            Entity entity = minecraft.level.getEntity(entry.getKey());
            if (entity instanceof InventoryVehicleEntity vehicle) {
                iterator.remove();
                applyVehicleInventory(vehicle, entry.getValue().entries());
                CruiseDebug.debug(ImmersiveAircraftCruise.LOGGER,
                        "[CruiseChunks][Client] applied pending vehicle inventory: vehicleId={}, entries={}",
                        entry.getKey(), entry.getValue().entries().size());
            } else if (now - entry.getValue().queuedTick() >= PENDING_ROUTE_STATE_TTL_TICKS) {
                iterator.remove();
            }
        }
    }

    private static void applyVehicleInventory(InventoryVehicleEntity vehicle,
                                               List<SyncVehicleInventoryPacket.Entry> entries) {
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
