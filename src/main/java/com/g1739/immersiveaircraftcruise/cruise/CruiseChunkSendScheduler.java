package com.g1739.immersiveaircraftcruise.cruise;

import com.g1739.immersiveaircraftcruise.CruiseDebug;
import com.g1739.immersiveaircraftcruise.ImmersiveAircraftCruise;
import com.g1739.immersiveaircraftcruise.mixin.ChunkHolderAccessor;
import com.g1739.immersiveaircraftcruise.mixin.ChunkMapInvoker;
import com.g1739.immersiveaircraftcruise.mixin.ServerChunkCacheInvoker;
import com.g1739.immersiveaircraftcruise.mixin.ServerLevelAccessor;
import com.g1739.immersiveaircraftcruise.network.CruiseNetwork;
import com.g1739.immersiveaircraftcruise.network.UpdateCruiseRoutePacket;
import immersive_aircraft.entity.VehicleEntity;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTaskPriorityQueueSorter;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityTeleportEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;

public final class CruiseChunkSendScheduler {
    private static final int ROUTE_LOOKAHEAD_CHUNKS = 50;
    private static final int VANILLA_CACHE_RADIUS = 32;
    private static final int ROUTE_CACHE_RADIUS = ROUTE_LOOKAHEAD_CHUNKS + VANILLA_CACHE_RADIUS + 2;
    private static final int ROUTE_TASK_PRIORITY = 0;
    /** Two consecutive route slices share one queue level, preserving a near-to-far distance ladder. */
    private static final int ROUTE_PRIORITY_SLICE_SPAN = 2;
    private static final int ROUTE_MAX_TASK_PRIORITY = ROUTE_LOOKAHEAD_CHUNKS / ROUTE_PRIORITY_SLICE_SPAN;
    /** Keep the route stream ahead of high-speed flight while the client applies packets one per tick. */
    private static final int ROUTE_PACKETS_PER_SERVER_TICK = 4;
    private static final int ROUTE_PACKET_WINDOW = 32;
    /** Keep the distant corridor ticketed while the separate FULL ticket supplies terrain packets. */
    private static final int ROUTE_PRELOAD_TICKET_LEVEL = 34;
    private static final int ROUTE_FULL_TICKET_LEVEL = 33;
    /** Keep the aircraft's current physical chunk entity-ticking. */
    private static final int ROUTE_ENTITY_TICK_TICKET_LEVEL = 31;
    /** The complete route look-ahead must be FULL so the aircraft never outruns the custom stream. */
    private static final int ROUTE_FULL_SLICE_COUNT = ROUTE_LOOKAHEAD_CHUNKS + 1;
    /** Extra acceleration is available once more than ten route chunks are FULL. */
    private static final int ROUTE_ACCELERATION_MIN_FULL_CHUNKS = 10;
    /** The client applies at most one full route packet each tick. */
    private static final long ROUTE_PACKET_ACK_TIMEOUT_TICKS = 80L;
    private static final int DIAGNOSTIC_INTERVAL_TICKS = 20;
    private static final TicketType<ChunkPos> ROUTE_PRELOAD_TICKET = TicketType.create(
            "iacruise_route_preload", Comparator.comparingLong(ChunkPos::toLong));
    private static final TicketType<ChunkPos> ROUTE_FULL_TICKET = TicketType.create(
            "iacruise_route_full", Comparator.comparingLong(ChunkPos::toLong));
    private static final TicketType<Integer> ROUTE_ENTITY_TICK_TICKET = TicketType.create(
            "iacruise_route_entity_tick", Comparator.comparingInt(Integer::intValue));
    private static final Map<ServerPlayer, PlayerState> NETWORK_STATES = new IdentityHashMap<>();
    private static final Map<ServerPlayer, String> CONTEXT_STATUSES = new IdentityHashMap<>();
    private static final Map<ServerPlayer, PendingTeleport> PENDING_TELEPORTS = new IdentityHashMap<>();
    private static final Map<ChunkMap, LoadingState> LOADING_STATES = new IdentityHashMap<>();
    private static final Map<VehicleEntity, EntityTickTicketState> ENTITY_TICK_TICKETS = new IdentityHashMap<>();
    private static final Map<VehicleEntity, EntityTickDiagnostic> ENTITY_TICK_DIAGNOSTICS = new IdentityHashMap<>();
    private static final Map<ServerLevel, Set<Long>> CACHE_INVALIDATIONS = new ConcurrentHashMap<>();

    private CruiseChunkSendScheduler() {
    }

    public static void register() {
        MinecraftForge.EVENT_BUS.addListener(CruiseChunkSendScheduler::onServerTick);
        MinecraftForge.EVENT_BUS.addListener(CruiseChunkSendScheduler::onEntityTeleport);
        MinecraftForge.EVENT_BUS.addListener(CruiseChunkSendScheduler::onPlayerChangedDimension);
        MinecraftForge.EVENT_BUS.addListener(CruiseChunkSendScheduler::onPlayerLoggedIn);
        MinecraftForge.EVENT_BUS.addListener(CruiseChunkSendScheduler::onPlayerStartTracking);
        MinecraftForge.EVENT_BUS.addListener(CruiseChunkSendScheduler::onPlayerLoggedOut);
        MinecraftForge.EVENT_BUS.addListener(CruiseChunkSendScheduler::onServerStopped);
        CruiseDebug.info(ImmersiveAircraftCruise.LOGGER, "[CruiseChunks] scheduler registered");
    }

    public static void invalidatePayloadCache(ServerLevel level, long chunkKey) {
        CACHE_INVALIDATIONS.computeIfAbsent(level, ignored -> ConcurrentHashMap.newKeySet()).add(chunkKey);
    }

    public static void onChunkFuturesUpdated(ChunkMap chunkMap, ChunkHolder holder) {
        LoadingState state = LOADING_STATES.get(chunkMap);
        if (state != null) {
            state.reprioritize(holder);
            state.logRouteChunkReady(holder);
        }
    }

    public static boolean suppressDuplicateChunkSend(ChunkMap chunkMap, ServerPlayer player, LevelChunk chunk) {
        PlayerState state = NETWORK_STATES.get(player);
        if (state == null
                || state.chunkMap != chunkMap
                || !state.priorityPath.corridorChunks().contains(chunk.getPos().toLong())) {
            return false;
        }
        NavigationContext navigation = navigationContext(player);
        if (navigation == null) {
            return false;
        }
        state.refreshPriorityPath(navigation);
        if (!state.priorityPath.corridorChunks().contains(chunk.getPos().toLong())) {
            return false;
        }
        state.synchronizeRouteCache(player, navigation);
        if (state.sendRouteChunkIfNeeded(player, chunk.getPos().toLong(), chunk, null,
                player.server.getTickCount()) > 0) {
            CruiseDebug.info(ImmersiveAircraftCruise.LOGGER,
                    "[CruiseChunks] immediate route packet: player={}, chunk={}, trigger=playerLoadedChunk",
                    player.getScoreboardName(), chunk.getPos());
        }
        state.duplicatePacketsSuppressed++;
        return true;
    }

    public static boolean suppressRouteChunkUnload(ChunkMap chunkMap, ServerPlayer player, ChunkPos chunkPos) {
        PlayerState state = NETWORK_STATES.get(player);
        if (state == null
                || state.chunkMap != chunkMap) {
            return false;
        }
        NavigationContext navigation = navigationContext(player);
        if (navigation == null) {
            return false;
        }
        state.refreshPriorityPath(navigation);
        if (!state.priorityPath.corridorChunks().contains(chunkPos.toLong())) {
            return false;
        }
        state.routeUnloadsSuppressed++;
        CruiseDebug.debug(ImmersiveAircraftCruise.LOGGER,
                "[CruiseChunks] route unload suppressed: player={}, chunk={}, corridor={}, count={}",
                player.getScoreboardName(), chunkPos, state.priorityPath.corridorChunks().size(),
                state.routeUnloadsSuppressed);
        return true;
    }

    public static void updateClientChunkState(ServerPlayer player, int x, int z, long hash,
                                               com.g1739.immersiveaircraftcruise.network.CruiseChunkStatePacket.State chunkState) {
        PlayerState state = NETWORK_STATES.get(player);
        if (state != null && state.chunkMap == player.serverLevel().getChunkSource().chunkMap) {
            long chunkKey = ChunkPos.asLong(x, z);
            CruiseChunkPayloadCache.Snapshot current = CruiseChunkPayloadCache.get(player.serverLevel()).get(chunkKey);
            long currentHash = current == null ? Long.MIN_VALUE : current.hash();
            long acknowledgedBefore = state.acknowledgedHashes.getOrDefault(chunkKey, Long.MIN_VALUE);
            long pendingBefore = state.pendingHashes.getOrDefault(chunkKey, Long.MIN_VALUE);
            boolean currentMatches = currentHash == hash;
            boolean knownSnapshot = acknowledgedBefore == hash || pendingBefore == hash;
            boolean inCorridor = state.priorityPath.corridorChunks().contains(chunkKey);
            if (chunkState == com.g1739.immersiveaircraftcruise.network.CruiseChunkStatePacket.State.ACTIVE) {
                if (knownSnapshot && currentMatches) {
                    state.acknowledgedHashes.put(chunkKey, hash);
                    state.pendingHashes.remove(chunkKey, hash);
                    state.pendingSentTicks.remove(chunkKey);
                    state.unreadyChunks.remove(chunkKey);
                    state.payloadRequestedChunks.remove(chunkKey);
                } else if (knownSnapshot) {
                    state.clearChunkState(chunkKey);
                    if (inCorridor) {
                        state.unreadyChunks.add(chunkKey);
                    }
                    state.payloadRequestedChunks.add(chunkKey);
                }
            } else if (chunkState == com.g1739.immersiveaircraftcruise.network.CruiseChunkStatePacket.State.MISSING) {
                if (knownSnapshot || inCorridor) {
                    state.clearChunkState(chunkKey);
                    if (inCorridor) {
                        state.unreadyChunks.add(chunkKey);
                    }
                    state.payloadRequestedChunks.add(chunkKey);
                }
            } else if (knownSnapshot || inCorridor) {
                state.clearChunkState(chunkKey);
                if (inCorridor) {
                    state.unreadyChunks.add(chunkKey);
                }
            }
            CruiseDebug.debug(ImmersiveAircraftCruise.LOGGER,
                    "[CruiseChunks] client-state player={}, chunk={}/{}, state={}, hash={}, currentHash={}, "
                            + "currentMatches={}, beforeAck={}, beforePending={}, afterAck={}, afterPending={}, "
                            + "payloadRequested={}",
                    player.getScoreboardName(), x, z, chunkState, hash, currentHash, currentMatches,
                    acknowledgedBefore, pendingBefore,
                    state.acknowledgedHashes.getOrDefault(chunkKey, Long.MIN_VALUE),
                    state.pendingHashes.getOrDefault(chunkKey, Long.MIN_VALUE),
                    state.payloadRequestedChunks.contains(chunkKey));
        }
    }

    /**
     * Readiness for the extra acceleration granted by a preloaded route. The
     * vehicle's normal route control never waits on this result; a failed
     * permit only removes extra power and lets an airplane apply its native
     * brake input.
     */
    public static boolean isAccelerationReady(VehicleEntity vehicle, CruiseRoute route) {
        NavigationContext navigation = new NavigationContext(vehicle, route,
                vehicle.getX(), vehicle.getY(), vehicle.getZ());
        RoutePath path = buildRoutePath(navigation, ROUTE_LOOKAHEAD_CHUNKS);
        ChunkMap chunkMap = ((ServerLevel) vehicle.level()).getChunkSource().chunkMap;
        int fullChunks = 0;
        for (long chunkKey : path.corridorChunks()) {
            if (findFullChunk(chunkMap, chunkKey) != null) {
                fullChunks++;
            }
        }
        return fullChunks > ROUTE_ACCELERATION_MIN_FULL_CHUNKS;
    }

    /** Keeps an active player-controlled cruise aircraft in its current entity-ticking chunk. */
    static void updateEntityTickingTicket(VehicleEntity vehicle) {
        EntityTickTicketState previous = ENTITY_TICK_TICKETS.get(vehicle);
        if (!requiresEntityTicking(vehicle)) {
            if (previous != null) {
                releaseEntityTickingTicket(vehicle, previous);
            }
            return;
        }

        ServerLevel level = (ServerLevel) vehicle.level();
        ServerChunkCache chunkSource = level.getChunkSource();
        ChunkMap chunkMap = chunkSource.chunkMap;
        LinkedHashSet<Long> desiredChunks = new LinkedHashSet<>();
        desiredChunks.add(vehicle.chunkPosition().toLong());
        if (previous != null
                && previous.chunkSource() == chunkSource
                && previous.chunkKeys().equals(desiredChunks)) {
            return;
        }

        DistanceManager distanceManager = ((ServerChunkCacheInvoker) chunkSource)
                .iacruise$getDistanceManager();
        boolean previousSourceChanged = previous != null && previous.chunkSource() != chunkSource;
        if (previous != null) {
            DistanceManager previousDistanceManager = previousSourceChanged
                    ? ((ServerChunkCacheInvoker) previous.chunkSource()).iacruise$getDistanceManager()
                    : distanceManager;
            for (long chunkKey : previous.chunkKeys()) {
                if (previousSourceChanged || !desiredChunks.contains(chunkKey)) {
                    removeEntityTickingTicket(previousDistanceManager, new ChunkPos(chunkKey), previous.vehicleId());
                }
            }
        }
        for (long chunkKey : desiredChunks) {
            if (previous == null || previousSourceChanged || !previous.chunkKeys().contains(chunkKey)) {
                ChunkPos chunkPos = new ChunkPos(chunkKey);
                distanceManager.addTicket(ROUTE_ENTITY_TICK_TICKET, chunkPos,
                        ROUTE_ENTITY_TICK_TICKET_LEVEL, vehicle.getId());
            }
        }
        ENTITY_TICK_TICKETS.put(vehicle, new EntityTickTicketState(chunkSource, desiredChunks, vehicle.getId()));
        if (previousSourceChanged) {
            DistanceManager previousDistanceManager = ((ServerChunkCacheInvoker) previous.chunkSource())
                    .iacruise$getDistanceManager();
            previousDistanceManager.runAllUpdates(previous.chunkSource().chunkMap);
        }
        distanceManager.runAllUpdates(chunkMap);
    }

    private static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        applyPendingTeleports(event.getServer());
        processCacheInvalidations();
        Map<ServerPlayer, NavigationContext> activeByPlayer = activeNavigationContexts(event.getServer());
        updateEntityTickingTickets(activeByPlayer);
        logEntityTickDiagnostics(event.getServer(), activeByPlayer);
        updateAccelerationPermits(activeByPlayer);
        updateLoadingPriorities(activeByPlayer);
        updateNetworkQueues(event.getServer(), activeByPlayer);
    }

    private static void updateAccelerationPermits(Map<ServerPlayer, NavigationContext> activeByPlayer) {
        Set<NavigationContext> activeFlights = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        activeFlights.addAll(activeByPlayer.values());
        for (NavigationContext navigation : activeFlights) {
            CruiseController.updatePreloadAccelerationPermit(navigation.vehicle());
        }
    }

    private static void updateEntityTickingTickets(Map<ServerPlayer, NavigationContext> activeByPlayer) {
        Set<VehicleEntity> activeVehicles = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (NavigationContext navigation : activeByPlayer.values()) {
            activeVehicles.add(navigation.vehicle());
        }
        for (VehicleEntity vehicle : activeVehicles) {
            updateEntityTickingTicket(vehicle);
        }

        Iterator<Map.Entry<VehicleEntity, EntityTickTicketState>> iterator = ENTITY_TICK_TICKETS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<VehicleEntity, EntityTickTicketState> entry = iterator.next();
            if (activeVehicles.contains(entry.getKey())) {
                continue;
            }
            EntityTickTicketState state = entry.getValue();
            DistanceManager distanceManager = ((ServerChunkCacheInvoker) state.chunkSource())
                    .iacruise$getDistanceManager();
            removeEntityTickingTicket(distanceManager, state);
            distanceManager.runAllUpdates(state.chunkSource().chunkMap);
            iterator.remove();
        }
        ENTITY_TICK_DIAGNOSTICS.keySet().removeIf(vehicle -> !activeVehicles.contains(vehicle));
    }

    private static void logEntityTickDiagnostics(MinecraftServer server,
                                                 Map<ServerPlayer, NavigationContext> activeByPlayer) {
        if (!CruiseDebug.enabled()) {
            return;
        }
        Set<VehicleEntity> activeVehicles = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (NavigationContext navigation : activeByPlayer.values()) {
            activeVehicles.add(navigation.vehicle());
        }
        long serverTick = server.getTickCount();
        for (VehicleEntity vehicle : activeVehicles) {
            ServerLevel level = (ServerLevel) vehicle.level();
            ServerChunkCache chunkSource = level.getChunkSource();
            long chunkKey = vehicle.chunkPosition().toLong();
            ChunkHolder holder = findLoadingHolder(chunkSource.chunkMap, chunkKey);
            DistanceManager distanceManager = ((ServerChunkCacheInvoker) chunkSource).iacruise$getDistanceManager();
            boolean entityListContains = ((ServerLevelAccessor) level).iacruise$getEntityTickList().contains(vehicle);
            boolean entityTickingRange = distanceManager.inEntityTickingRange(chunkKey);
            boolean positionTicking = chunkSource.isPositionTicking(chunkKey);
            boolean entitiesLoaded = level.areEntitiesLoaded(chunkKey);
            boolean positionEntityTicking = level.isPositionEntityTicking(vehicle.blockPosition());
            boolean entityFutureReady = false;
            boolean entityFutureDone = false;
            if (holder != null) {
                var future = holder.getEntityTickingChunkFuture();
                entityFutureDone = future.isDone();
                if (entityFutureDone && !future.isCompletedExceptionally()) {
                    var result = future.getNow(null);
                    entityFutureReady = result != null && result.left().isPresent();
                }
            }
            EntityTickTicketState ticketState = ENTITY_TICK_TICKETS.get(vehicle);
            boolean ownsEntityTicket = ticketState != null && ticketState.chunkKeys().contains(chunkKey);
            String holderStatus = holder == null ? "missing" : holder.getFullStatus().toString();
            String signature = entityListContains + ":" + entityTickingRange + ":" + positionTicking + ":"
                    + entitiesLoaded + ":" + positionEntityTicking + ":" + entityFutureDone + ":"
                    + entityFutureReady + ":" + ownsEntityTicket + ":" + holderStatus + ":" + vehicle.isRemoved();
            EntityTickDiagnostic previous = ENTITY_TICK_DIAGNOSTICS.get(vehicle);
            boolean progressed = previous == null || previous.vehicleTick() != vehicle.tickCount;
            int stalledTicks = progressed ? 0 : previous.stalledTicks() + 1;
            String state = !entityListContains ? "not-in-entity-tick-list"
                    : (!entityTickingRange ? "not-in-entity-ticking-range"
                    : (progressed ? "ticking" : "eligible-but-tick-not-observed"));
            boolean recovered = previous != null && previous.stalledTicks() >= 20 && progressed;
            boolean changed = previous == null || !signature.equals(previous.signature());
            boolean periodicWarning = stalledTicks >= 20
                    && (previous == null || serverTick - previous.lastLoggedServerTick() >= 20);
            boolean periodicInfo = progressed
                    && (previous == null || serverTick - previous.lastLoggedServerTick() >= 20);
            if (changed || recovered || periodicWarning || periodicInfo) {
                String message = "[CruiseEntityTick] serverTick={}, vehicleId={}, vehicleTick={}, stalledTicks={}, "
                        + "chunk={}, reason={}, entityTickList={}, entityTickingRange={}, positionTicking={}, "
                        + "entitiesLoaded={}, positionEntityTicking={}, entityFutureDone={}, entityFutureReady={}, "
                        + "holderStatus={}, holderTicketLevel={}, entityTicketOwned={}, removed={}, passengers={}, pilot={}";
                if (stalledTicks >= 20) {
                    ImmersiveAircraftCruise.LOGGER.warn(message, serverTick, vehicle.getId(), vehicle.tickCount,
                            stalledTicks, vehicle.chunkPosition(), state, entityListContains, entityTickingRange,
                            positionTicking, entitiesLoaded, positionEntityTicking, entityFutureDone,
                            entityFutureReady, holderStatus,
                            holder == null ? -1 : holder.getTicketLevel(), ownsEntityTicket, vehicle.isRemoved(),
                            vehicle.getPassengers().size(), vehicle.getControllingPassenger());
                } else {
                    CruiseDebug.info(ImmersiveAircraftCruise.LOGGER, message, serverTick, vehicle.getId(),
                            vehicle.tickCount, stalledTicks, vehicle.chunkPosition(), state, entityListContains,
                            entityTickingRange, positionTicking, entitiesLoaded, positionEntityTicking,
                            entityFutureDone, entityFutureReady, holderStatus,
                            holder == null ? -1 : holder.getTicketLevel(), ownsEntityTicket, vehicle.isRemoved(),
                            vehicle.getPassengers().size(), vehicle.getControllingPassenger());
                }
                ENTITY_TICK_DIAGNOSTICS.put(vehicle,
                        new EntityTickDiagnostic(vehicle.tickCount, stalledTicks, serverTick, signature));
            } else {
                ENTITY_TICK_DIAGNOSTICS.put(vehicle,
                        new EntityTickDiagnostic(vehicle.tickCount, stalledTicks,
                                previous.lastLoggedServerTick(), signature));
            }
        }
    }

    private static boolean requiresEntityTicking(VehicleEntity vehicle) {
        if (vehicle.level().isClientSide()
                || !CruiseController.hasCruiseModule(vehicle)
                || !(vehicle.getControllingPassenger() instanceof ServerPlayer)) {
            return false;
        }
        CruiseRoute route = CruiseController.currentRoute(vehicle);
        return route.isEnabled()
                && !route.isHoldingPattern()
                && route.hasTarget()
                && route.getSelectedEntry().loadingMode() != CruiseRoute.RouteLoadingMode.VANILLA;
    }

    private static void releaseEntityTickingTicket(VehicleEntity vehicle, EntityTickTicketState state) {
        DistanceManager distanceManager = ((ServerChunkCacheInvoker) state.chunkSource())
                .iacruise$getDistanceManager();
        removeEntityTickingTicket(distanceManager, state);
        ENTITY_TICK_TICKETS.remove(vehicle);
        distanceManager.runAllUpdates(state.chunkSource().chunkMap);
    }

    private static void removeEntityTickingTicket(DistanceManager distanceManager, EntityTickTicketState state) {
        for (long chunkKey : state.chunkKeys()) {
            removeEntityTickingTicket(distanceManager, new ChunkPos(chunkKey), state.vehicleId());
        }
    }

    private static void removeEntityTickingTicket(DistanceManager distanceManager, ChunkPos chunkPos, int vehicleId) {
        distanceManager.removeTicket(ROUTE_ENTITY_TICK_TICKET, chunkPos,
                ROUTE_ENTITY_TICK_TICKET_LEVEL, vehicleId);
    }

    /**
     * Builds one route context per active aircraft and maps every onboard
     * viewer to that context. Route geometry and server tickets are aircraft
     * state; acknowledgement and packet delivery remain viewer state.
     */
    private static Map<ServerPlayer, NavigationContext> activeNavigationContexts(MinecraftServer server) {
        IdentityHashMap<VehicleEntity, NavigationContext> byVehicle = new IdentityHashMap<>();
        IdentityHashMap<ServerPlayer, NavigationContext> byPlayer = new IdentityHashMap<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            NavigationContext navigation = navigationContext(player);
            logNavigationContext(player, navigation);
            if (navigation == null) {
                continue;
            }
            NavigationContext canonical = byVehicle.computeIfAbsent(navigation.vehicle(), ignored -> navigation);
            byPlayer.put(player, canonical);
        }
        return byPlayer;
    }

    private static void processCacheInvalidations() {
        for (ServerLevel level : CACHE_INVALIDATIONS.keySet()) {
            Set<Long> chunkKeys = CACHE_INVALIDATIONS.remove(level);
            if (chunkKeys == null || chunkKeys.isEmpty()) {
                continue;
            }
            CruiseChunkPayloadCache cache = CruiseChunkPayloadCache.get(level);
            for (long chunkKey : chunkKeys) {
                cache.invalidate(chunkKey);
                for (Map.Entry<ServerPlayer, PlayerState> entry : NETWORK_STATES.entrySet()) {
                    if (entry.getKey().serverLevel() == level) {
                        entry.getValue().invalidateChunk(chunkKey);
                    }
                }
            }
        }
    }

    private static void updateLoadingPriorities(Map<ServerPlayer, NavigationContext> activeByPlayer) {
        Map<ChunkMap, DesiredLoading> desiredByMap = new IdentityHashMap<>();
        Set<NavigationContext> activeFlights = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        activeFlights.addAll(activeByPlayer.values());
        for (NavigationContext navigation : activeFlights) {
            ServerChunkCache chunkSource = ((ServerLevel) navigation.vehicle().level()).getChunkSource();
            ChunkMap chunkMap = chunkSource.chunkMap;
            DesiredLoading desired = desiredByMap.computeIfAbsent(chunkMap,
                    ignored -> new DesiredLoading(chunkSource));
            RoutePath path = buildRoutePath(navigation, ROUTE_LOOKAHEAD_CHUNKS);
            desired.routeSlices.addAll(path.slices());
            for (Map.Entry<Long, Integer> entry
                    : buildPriorityRanks(path).entrySet()) {
                desired.priorityChunks.merge(entry.getKey(), entry.getValue(), Math::min);
            }
        }

        Iterator<Map.Entry<ChunkMap, LoadingState>> iterator = LOADING_STATES.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<ChunkMap, LoadingState> entry = iterator.next();
            DesiredLoading desired = desiredByMap.remove(entry.getKey());
            if (desired == null || desired.chunkSource != entry.getValue().chunkSource) {
                entry.getValue().clear();
                iterator.remove();
            } else {
                entry.getValue().synchronize(desired.priorityChunks, desired.routeSlices);
            }
        }

        for (Map.Entry<ChunkMap, DesiredLoading> entry : desiredByMap.entrySet()) {
            DesiredLoading desired = entry.getValue();
            LoadingState state = new LoadingState(desired.chunkSource, entry.getKey());
            state.synchronize(desired.priorityChunks, desired.routeSlices);
            LOADING_STATES.put(entry.getKey(), state);
        }
    }

    private static void updateNetworkQueues(MinecraftServer server,
                                             Map<ServerPlayer, NavigationContext> activeByPlayer) {
        Set<ServerPlayer> activePlayers = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            NavigationContext navigation = activeByPlayer.get(player);
            if (navigation == null) {
                continue;
            }

            activePlayers.add(player);
            PlayerState state = NETWORK_STATES.get(player);
            if (state == null || !isStateValid(player, state)) {
                state = new PlayerState(player.serverLevel().getChunkSource().chunkMap);
                NETWORK_STATES.put(player, state);
            }
            state.refreshPriorityPath(navigation);
            state.synchronizeRouteCache(player, navigation);
            int sentCount = state.sendPriorityChunks(player, server.getTickCount());
            state.logStatus(player, navigation, sentCount, server.getTickCount());
        }

        Iterator<Map.Entry<ServerPlayer, PlayerState>> iterator = NETWORK_STATES.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<ServerPlayer, PlayerState> entry = iterator.next();
            if (!activePlayers.contains(entry.getKey())) {
                entry.getValue().disableRouteCache(entry.getKey());
                iterator.remove();
            }
        }
    }

    private static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            CruiseController.handlePilotLoggedOut(player);
            stopPilotNavigation(player, "logout");
            PENDING_TELEPORTS.remove(player);
            NETWORK_STATES.remove(player);
            CONTEXT_STATUSES.remove(player);
        }
    }

    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        Entity root = player.getRootVehicle();
        if (root instanceof VehicleEntity vehicle && CruiseController.hasCruiseModule(vehicle)) {
            sendAuthoritativeRoute(player, vehicle);
        }
    }

    private static void onPlayerStartTracking(PlayerEvent.StartTracking event) {
        if (event.getEntity() instanceof ServerPlayer player
                && event.getTarget() instanceof VehicleEntity vehicle
                && CruiseController.hasCruiseModule(vehicle)) {
            sendAuthoritativeRoute(player, vehicle);
        }
    }

    private static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            CruiseController.handlePilotLoggedOut(player);
            stopPilotNavigation(player, "dimension-change");
            PENDING_TELEPORTS.remove(player);
            NETWORK_STATES.remove(player);
            CONTEXT_STATUSES.remove(player);
        }
    }

    private static void onEntityTeleport(EntityTeleportEvent event) {
        Entity entity = event.getEntity();
        if (entity.level().isClientSide()) {
            return;
        }
        if (entity instanceof ServerPlayer player) {
            queueSeatedTeleport(player, event.getTargetX(), event.getTargetY(), event.getTargetZ());
            return;
        }
        if (entity instanceof VehicleEntity vehicle && CruiseController.hasCruiseModule(vehicle)) {
            for (Entity passenger : vehicle.getPassengers()) {
                if (passenger instanceof ServerPlayer player && CruiseController.isPilot(vehicle, player)) {
                    PENDING_TELEPORTS.put(player,
                            new PendingTeleport(vehicle, event.getTargetX(), event.getTargetY(), event.getTargetZ(),
                                    Vec3.ZERO, false));
                }
            }
        }
    }

    public static void queueSeatedTeleport(ServerPlayer player, double targetX, double targetY, double targetZ) {
        Entity root = player.getRootVehicle();
        if (root instanceof VehicleEntity vehicle
                && CruiseController.isPilot(vehicle, player)
                && CruiseController.hasCruiseModule(vehicle)) {
            Vec3 offset = player.position().subtract(vehicle.position());
            PENDING_TELEPORTS.put(player,
                    new PendingTeleport(vehicle, targetX, targetY, targetZ, offset, true));
        }
    }

    /**
     * Completes a same-dimension player teleport before the next entity tick.
     * ServerPlayer.teleportTo(ServerLevel, ...) calls stopRiding() first, so
     * waiting for the next tick would make the pilot check fail and let the
     * aircraft continue at its old position.
     */
    public static void applyPlayerTeleport(ServerPlayer player, ServerLevel targetLevel,
                                            double targetX, double targetY, double targetZ) {
        PendingTeleport pending = PENDING_TELEPORTS.remove(player);
        if (pending == null || !pending.targetIsPlayer()) {
            return;
        }
        if (player.hasDisconnected()
                || pending.vehicle().isRemoved()
                || targetLevel != pending.vehicle().level()
                || player.level() != targetLevel
                || !CruiseController.hasCruiseModule(pending.vehicle())) {
            return;
        }
        if (player.position().distanceToSqr(targetX, targetY, targetZ) > 4.0d) {
            CruiseDebug.debug(ImmersiveAircraftCruise.LOGGER,
                    "[CruiseChunks] player teleport was not applied, ignoring: player={}, vehicleId={}, "
                            + "target={}/{}/{}, actual={}/{}/{}",
                    player.getScoreboardName(), pending.vehicle().getId(), targetX, targetY, targetZ,
                    player.getX(), player.getY(), player.getZ());
            return;
        }
        synchronizeSeatedTeleport(player, pending, targetX, targetY, targetZ);
    }

    private static void synchronizeSeatedTeleport(ServerPlayer player, PendingTeleport pending,
                                                   double targetX, double targetY, double targetZ) {
        VehicleEntity vehicle = pending.vehicle();
        double vehicleX = targetX - pending.passengerOffset().x;
        double vehicleY = targetY - pending.passengerOffset().y;
        double vehicleZ = targetZ - pending.passengerOffset().z;
        vehicle.moveTo(vehicleX, vehicleY, vehicleZ, vehicle.getYRot(), vehicle.getXRot());
        if (player.getVehicle() != vehicle && !player.startRiding(vehicle, true)) {
            CruiseDebug.debug(ImmersiveAircraftCruise.LOGGER,
                    "[CruiseChunks] could not reattach pilot after teleport: player={}, vehicleId={}",
                    player.getScoreboardName(), vehicle.getId());
            return;
        }
        vehicle.positionRider(player);
        player.serverLevel().getChunkSource().move(player);
        CruiseDebug.info(ImmersiveAircraftCruise.LOGGER,
                "[CruiseChunks] synchronized seated teleport: player={}, vehicleId={}, target={}/{}/{}, "
                        + "vehicle={}/{}/{}",
                player.getScoreboardName(), vehicle.getId(), targetX, targetY, targetZ,
                vehicle.getX(), vehicle.getY(), vehicle.getZ());
    }

    private static void applyPendingTeleports(MinecraftServer server) {
        if (PENDING_TELEPORTS.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<ServerPlayer, PendingTeleport>> iterator = PENDING_TELEPORTS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<ServerPlayer, PendingTeleport> entry = iterator.next();
            ServerPlayer player = entry.getKey();
            PendingTeleport pending = entry.getValue();
            iterator.remove();
            if (player.hasDisconnected()
                    || server.getPlayerList().getPlayer(player.getUUID()) != player
                    || player.level() != pending.vehicle().level()
                    || pending.vehicle().isRemoved()) {
                continue;
            }
            if ((!pending.targetIsPlayer() && !CruiseController.isPilot(pending.vehicle(), player))
                    || !CruiseController.hasCruiseModule(pending.vehicle())) {
                continue;
            }
            Vec3 teleportedEntityPosition = pending.targetIsPlayer()
                    ? player.position()
                    : pending.vehicle().position();
            if (teleportedEntityPosition.distanceToSqr(
                    pending.targetX(), pending.targetY(), pending.targetZ()) > 4.0d) {
                CruiseDebug.debug(ImmersiveAircraftCruise.LOGGER,
                        "[CruiseChunks] seated teleport did not reach target, ignoring: player={}, vehicleId={}, "
                                + "target={}/{}/{}, actual={}/{}/{}",
                        player.getScoreboardName(), pending.vehicle().getId(), pending.targetX(), pending.targetY(),
                        pending.targetZ(), teleportedEntityPosition.x, teleportedEntityPosition.y,
                        teleportedEntityPosition.z);
                continue;
            }
            synchronizeSeatedTeleport(player, pending,
                    pending.targetX(), pending.targetY(), pending.targetZ());
        }
    }

    private static void stopPilotNavigation(ServerPlayer player, String reason) {
        Entity root = player.getRootVehicle();
        if (!(root instanceof VehicleEntity vehicle)
                || !CruiseController.isPilot(vehicle, player)
                || !CruiseController.hasCruiseModule(vehicle)) {
            return;
        }
        CruiseRoute route = CruiseController.currentRoute(vehicle).copy();
        if (!route.isEnabled()) {
            sendAuthoritativeRoute(player, vehicle);
            return;
        }
        CruiseController.stopNavigation(vehicle, route, null);
        CruiseDebug.info(ImmersiveAircraftCruise.LOGGER,
                "[CruiseChunks] navigation stopped for pilot lifecycle: player={}, vehicleId={}, reason={}",
                player.getScoreboardName(), vehicle.getId(), reason);
    }

    private static void sendAuthoritativeRoute(ServerPlayer player, VehicleEntity vehicle) {
        CruiseNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new UpdateCruiseRoutePacket(vehicle.getId(), CruiseController.currentRoute(vehicle).copy()));
        CruiseController.syncVehicleInventoryToPlayer(vehicle, player);
        if (CruiseController.isPilot(vehicle, player)) {
            CruiseController.synchronizePreloadAccelerationPermit(vehicle, player);
        }
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        NETWORK_STATES.clear();
        PENDING_TELEPORTS.clear();
        CONTEXT_STATUSES.clear();
        ENTITY_TICK_TICKETS.clear();
        ENTITY_TICK_DIAGNOSTICS.clear();
        CACHE_INVALIDATIONS.clear();
        for (LoadingState state : LOADING_STATES.values()) {
            state.clear();
        }
        LOADING_STATES.clear();
    }

    private static boolean isStateValid(ServerPlayer player, PlayerState state) {
        return !player.hasDisconnected()
                && player.server.getPlayerList().getPlayer(player.getUUID()) == player
                && player.serverLevel().getChunkSource().chunkMap == state.chunkMap;
    }

    private static NavigationContext navigationContext(ServerPlayer player) {
        Entity rootVehicle = player.getRootVehicle();
        if (!(rootVehicle instanceof VehicleEntity vehicle)
                || !(vehicle instanceof CruiseVehicleAccess access)) {
            return null;
        }

        CruiseRoute route = access.iacruise$getRoute();
        if (route == null
                || !route.isEnabled()
                || route.isHoldingPattern()
                || !route.hasTarget()
                || route.getSelectedEntry().loadingMode() == CruiseRoute.RouteLoadingMode.VANILLA) {
            return null;
        }
        // The server-side vehicle is the movement authority used by the route controller.
        // The ServerPlayer passenger can remain at its old position during client-predicted
        // high-speed flight, so it must not be used as the route anchor or cache center.
        return new NavigationContext(vehicle, route,
                vehicle.getX(), vehicle.getY(), vehicle.getZ());
    }

    private static void logNavigationContext(ServerPlayer player, NavigationContext navigation) {
        if (!CruiseDebug.enabled()) {
            return;
        }
        String status;
        if (navigation != null) {
            CruiseRoute route = navigation.route();
            status = "active vehicle=" + navigation.vehicle().getClass().getSimpleName()
                    + ", enabled=" + route.isEnabled()
                    + ", holding=" + route.isHoldingPattern()
                    + ", hasTarget=" + route.hasTarget()
                    + ", routeIndex=" + route.getCurrentIndex()
                    + ", vehicleAnchor=true";
        } else {
            Entity rootVehicle = player.getRootVehicle();
            if (!(rootVehicle instanceof VehicleEntity vehicle)) {
                status = "inactive root=" + rootVehicle.getClass().getSimpleName();
            } else if (!(vehicle instanceof CruiseVehicleAccess access)) {
                status = "inactive vehicle=" + vehicle.getClass().getSimpleName() + ", noCruiseAccess";
            } else {
                CruiseRoute route = access.iacruise$getRoute();
                status = route == null
                        ? "inactive vehicle=" + vehicle.getClass().getSimpleName() + ", route=null"
                        : "inactive vehicle=" + vehicle.getClass().getSimpleName()
                        + ", enabled=" + route.isEnabled()
                        + ", holding=" + route.isHoldingPattern()
                        + ", hasTarget=" + route.hasTarget()
                        + ", routeIndex=" + route.getCurrentIndex();
            }
        }
        if (!status.equals(CONTEXT_STATUSES.put(player, status))) {
            CruiseDebug.info(ImmersiveAircraftCruise.LOGGER, "[CruiseChunks] player {} context: {}",
                    player.getScoreboardName(), status);
        }
    }

    private static String formatAnchor(NavigationContext navigation) {
        return String.format("%.2f/%.2f/%.2f",
                navigation.anchorX(), navigation.anchorY(), navigation.anchorZ());
    }

    private static RoutePath buildRoutePath(NavigationContext navigation, int lookaheadChunks) {
        int maxCenterChunks = lookaheadChunks + 1;
        LinkedHashMap<Long, SideOffset> centerChunks = new LinkedHashMap<>();
        CruiseRoute route = navigation.route();
        double startX = navigation.anchorX();
        double startZ = navigation.anchorZ();

        List<CruiseRoute.Waypoint> waypoints = route.getSelectedEntry().waypoints();
        boolean useLShaped = route.shouldUseLShaped(startX, startZ);
        boolean useLongAxisCenterline = route.isLongLRouteCandidate() && !useLShaped;
        if (useLShaped) {
            addLShapedSegments(centerChunks, route, startX, startZ, maxCenterChunks);
        } else if (useLongAxisCenterline) {
            CruiseRoute.Waypoint target = route.getNavigationTarget(startX, startZ);
            if (target != null) {
                double targetX = target.x() + 0.5d;
                double targetZ = target.z() + 0.5d;
                addSegmentCenters(centerChunks, startX, startZ, targetX, targetZ,
                        maxCenterChunks, SideOffset.ZERO);
            }
        } else {
            int firstWaypoint = firstForwardWaypoint(route, waypoints, startX, startZ);
            for (int index = firstWaypoint; index < waypoints.size()
                    && centerChunks.size() < maxCenterChunks; index++) {
                CruiseRoute.Waypoint waypoint = waypoints.get(index);
                double targetX = waypoint.x() + 0.5d;
                double targetZ = waypoint.z() + 0.5d;
                double deltaX = targetX - startX;
                double deltaZ = targetZ - startZ;
                if (deltaX != 0.0d || deltaZ != 0.0d) {
                    addSegmentCenters(centerChunks, startX, startZ, targetX, targetZ,
                            maxCenterChunks, SideOffset.forSegment(deltaX, deltaZ));
                }
                startX = targetX;
                startZ = targetZ;
            }
        }

        LinkedHashSet<Long> corridorChunks = new LinkedHashSet<>();
        List<RouteSlice> slices = new ArrayList<>(centerChunks.size());
        for (Map.Entry<Long, SideOffset> entry : centerChunks.entrySet()) {
            long centerKey = entry.getKey();
            int centerX = ChunkPos.getX(centerKey);
            int centerZ = ChunkPos.getZ(centerKey);
            SideOffset offset = entry.getValue();
            RouteSlice slice = new RouteSlice(
                    centerKey,
                    ChunkPos.asLong(centerX + offset.x(), centerZ + offset.z()),
                    ChunkPos.asLong(centerX - offset.x(), centerZ - offset.z()));
            slices.add(slice);
            for (int index = 0; index < RouteSlice.WIDTH; index++) {
                corridorChunks.add(slice.chunkKey(index));
            }
        }
        return new RoutePath(corridorChunks, List.copyOf(slices));
    }

    /**
     * Builds the remaining geometric L corridor from the server vehicle
     * position. The controller's L stage is client-authoritative, so progress
     * is inferred from the vehicle's position relative to the two route legs.
     */
    private static void addLShapedSegments(Map<Long, SideOffset> centerChunks,
                                           CruiseRoute route,
                                           double startX, double startZ,
                                           int maxCenterChunks) {
        CruiseRoute.Waypoint corner = route.getLCornerTarget();
        CruiseRoute.Waypoint finalLine = route.getLFinalLineTarget();
        if (corner == null || finalLine == null) {
            return;
        }

        double cornerX = corner.x() + 0.5d;
        double cornerZ = corner.z() + 0.5d;
        double finalX = finalLine.x() + 0.5d;
        double finalZ = finalLine.z() + 0.5d;
        boolean firstAxisX = route.isLFirstAxisX();
        double finalAxisProgress = (firstAxisX ? startZ - cornerZ : startX - cornerX)
                * route.lSecondAxisSign();
        double distanceFromFirstLeg = Math.abs(firstAxisX ? startZ - cornerZ : startX - cornerX);
        double distanceFromFinalLeg = Math.abs(firstAxisX ? startX - cornerX : startZ - cornerZ);

        if (finalAxisProgress <= 0.0d || distanceFromFinalLeg > distanceFromFirstLeg) {
            double firstAxisPointX = firstAxisX ? cornerX : startX;
            double firstAxisPointZ = firstAxisX ? startZ : cornerZ;
            addSegmentCenters(centerChunks, startX, startZ,
                    firstAxisPointX, firstAxisPointZ, maxCenterChunks, SideOffset.ZERO);
            if (centerChunks.size() >= maxCenterChunks) {
                return;
            }
            addSegmentCenters(centerChunks, firstAxisPointX, firstAxisPointZ,
                    cornerX, cornerZ, maxCenterChunks, SideOffset.ZERO);
            if (centerChunks.size() >= maxCenterChunks) {
                return;
            }
            startX = cornerX;
            startZ = cornerZ;
        }

        addSegmentCenters(centerChunks, startX, startZ, finalX, finalZ,
                maxCenterChunks, SideOffset.ZERO);
    }

    private static int firstForwardWaypoint(CruiseRoute route, List<CruiseRoute.Waypoint> waypoints,
                                             double vehicleX, double vehicleZ) {
        int index = route.getCurrentIndex();
        while (index < waypoints.size() && isPastWaypoint(route, waypoints, index, vehicleX, vehicleZ)) {
            index++;
        }
        return index;
    }

    private static boolean isPastWaypoint(CruiseRoute route, List<CruiseRoute.Waypoint> waypoints,
                                           int index, double vehicleX, double vehicleZ) {
        CruiseRoute.Waypoint previous = index == 0
                ? route.getStartPoint()
                : waypoints.get(index - 1);
        if (previous == null) {
            return false;
        }

        CruiseRoute.Waypoint target = waypoints.get(index);
        double startX = previous.x() + 0.5d;
        double startZ = previous.z() + 0.5d;
        double targetX = target.x() + 0.5d;
        double targetZ = target.z() + 0.5d;
        double deltaX = targetX - startX;
        double deltaZ = targetZ - startZ;
        double lengthSquared = deltaX * deltaX + deltaZ * deltaZ;
        if (lengthSquared < 1.0d) {
            return false;
        }

        double projection = ((vehicleX - startX) * deltaX + (vehicleZ - startZ) * deltaZ) / lengthSquared;
        double distanceFromTargetX = vehicleX - targetX;
        double distanceFromTargetZ = vehicleZ - targetZ;
        return projection > 1.0d
                && distanceFromTargetX * distanceFromTargetX + distanceFromTargetZ * distanceFromTargetZ > 256.0d;
    }

    private static LinkedHashMap<Long, Integer> buildPriorityRanks(RoutePath path) {
        LinkedHashMap<Long, Integer> priorities = new LinkedHashMap<>();
        for (int sliceIndex = 0; sliceIndex < path.slices().size(); sliceIndex++) {
            RouteSlice slice = path.slices().get(sliceIndex);
            int priority = Math.min(
                    ROUTE_TASK_PRIORITY + sliceIndex / ROUTE_PRIORITY_SLICE_SPAN,
                    ROUTE_MAX_TASK_PRIORITY);
            priorities.merge(slice.center(), priority, Math::min);
            priorities.merge(slice.left(), priority, Math::min);
            priorities.merge(slice.right(), priority, Math::min);
        }
        return priorities;
    }

    private static void addSegmentCenters(Map<Long, SideOffset> chunks,
                                          double startX, double startZ,
                                          double targetX, double targetZ,
                                          int maxCenterChunks, SideOffset sideOffset) {
        int chunkX = chunkCoordinate(startX);
        int chunkZ = chunkCoordinate(startZ);
        chunks.put(ChunkPos.asLong(chunkX, chunkZ), sideOffset);
        if (chunks.size() >= maxCenterChunks) {
            return;
        }

        int targetChunkX = chunkCoordinate(targetX);
        int targetChunkZ = chunkCoordinate(targetZ);
        double deltaX = targetX - startX;
        double deltaZ = targetZ - startZ;
        int stepX = Double.compare(deltaX, 0.0d);
        int stepZ = Double.compare(deltaZ, 0.0d);
        double tDeltaX = stepX == 0 ? Double.POSITIVE_INFINITY : 16.0d / Math.abs(deltaX);
        double tDeltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : 16.0d / Math.abs(deltaZ);
        double nextBoundaryX = stepX > 0 ? (chunkX + 1) * 16.0d : chunkX * 16.0d;
        double nextBoundaryZ = stepZ > 0 ? (chunkZ + 1) * 16.0d : chunkZ * 16.0d;
        double tMaxX = stepX == 0 ? Double.POSITIVE_INFINITY : (nextBoundaryX - startX) / deltaX;
        double tMaxZ = stepZ == 0 ? Double.POSITIVE_INFINITY : (nextBoundaryZ - startZ) / deltaZ;

        while ((chunkX != targetChunkX || chunkZ != targetChunkZ) && chunks.size() < maxCenterChunks) {
            int comparison = Double.compare(tMaxX, tMaxZ);
            // A corner crossing enters the diagonal cell directly; it is one route center, not two side cells.
            if (comparison <= 0) {
                chunkX += stepX;
                tMaxX += tDeltaX;
            }
            if (comparison >= 0) {
                chunkZ += stepZ;
                tMaxZ += tDeltaZ;
            }
            chunks.putIfAbsent(ChunkPos.asLong(chunkX, chunkZ), sideOffset);
        }
    }

    private static int chunkCoordinate(double blockCoordinate) {
        return Math.floorDiv((int) Math.floor(blockCoordinate), 16);
    }

    private static LevelChunk findFullChunk(ChunkMap chunkMap, long chunkKey) {
        ChunkHolder holder = findLoadingHolder(chunkMap, chunkKey);
        return holder == null ? null : holder.getFullChunk();
    }

    private static CruiseChunkPayloadCache.Snapshot prepareRouteChunk(PlayerState state,
                                                                       ServerPlayer player,
                                                                       LevelChunk chunk) {
        CruiseChunkPayloadCache cache = CruiseChunkPayloadCache.get(player.serverLevel());
        long chunkKey = chunk.getPos().toLong();
        CruiseChunkPayloadCache.Snapshot snapshot = cache.get(chunkKey);
        if (snapshot == null) {
            ClientboundLevelChunkWithLightPacket packet = new ClientboundLevelChunkWithLightPacket(
                    chunk, ((ChunkMapInvoker) state.chunkMap).iacruise$getLightEngine(), null, null);
            byte[] encoded = CruiseChunkPayloadCodec.encode(packet);
            byte[] compressed = CruiseChunkPayloadCodec.compress(encoded);
            snapshot = new CruiseChunkPayloadCache.Snapshot(
                    CruiseChunkPayloadCodec.hash(encoded), compressed);
            cache.put(chunkKey, snapshot);
        }
        return snapshot;
    }

    private static void changeTaskPriority(ChunkMap chunkMap, ChunkHolder holder, int targetPriority) {
        ChunkHolderAccessor holderAccessor = (ChunkHolderAccessor) holder;
        int currentPriority = holderAccessor.iacruise$getQueueLevel();
        if (currentPriority == targetPriority) {
            return;
        }

        ChunkTaskPriorityQueueSorter queueSorter = ((ChunkMapInvoker) chunkMap).iacruise$getQueueSorter();
        queueSorter.onLevelChange(
                holder.getPos(),
                () -> currentPriority,
                targetPriority,
                holderAccessor::iacruise$setQueueLevel);
        holderAccessor.iacruise$setQueueLevel(targetPriority);
    }

    private static ChunkHolder findLoadingHolder(ChunkMap chunkMap, long chunkKey) {
        ChunkMapInvoker invoker = (ChunkMapInvoker) chunkMap;
        ChunkHolder holder = invoker.iacruise$getVisibleChunkIfPresent(chunkKey);
        return holder != null ? holder : invoker.iacruise$getUpdatingChunkIfPresent(chunkKey);
    }

    private record NavigationContext(VehicleEntity vehicle, CruiseRoute route,
                                     double anchorX, double anchorY, double anchorZ) {
    }

    private record EntityTickTicketState(ServerChunkCache chunkSource, LinkedHashSet<Long> chunkKeys, int vehicleId) {
    }

    private record EntityTickDiagnostic(int vehicleTick, int stalledTicks,
                                        long lastLoggedServerTick, String signature) {
    }

    private record PendingTeleport(VehicleEntity vehicle, double targetX, double targetY, double targetZ,
                                   Vec3 passengerOffset, boolean targetIsPlayer) {
    }

    private record SideOffset(int x, int z) {
        private static final SideOffset ZERO = new SideOffset(0, 0);

        private static SideOffset forSegment(double deltaX, double deltaZ) {
            double scale = Math.max(Math.abs(deltaX), Math.abs(deltaZ));
            return new SideOffset(
                    (int) Math.round(-deltaZ / scale),
                    (int) Math.round(deltaX / scale));
        }
    }

    private record RouteSlice(long center, long left, long right) {
        private static final int WIDTH = 3;

        private long chunkKey(int index) {
            return switch (index) {
                case 0 -> center;
                case 1 -> left;
                case 2 -> right;
                default -> throw new IndexOutOfBoundsException(index);
            };
        }
    }

    private record RoutePath(LinkedHashSet<Long> corridorChunks, List<RouteSlice> slices) {
        private static RoutePath empty() {
            return new RoutePath(new LinkedHashSet<>(), List.of());
        }
    }

    private static final class DesiredLoading {
        private final ServerChunkCache chunkSource;
        private final LinkedHashMap<Long, Integer> priorityChunks = new LinkedHashMap<>();
        private final List<RouteSlice> routeSlices = new ArrayList<>();

        private DesiredLoading(ServerChunkCache chunkSource) {
            this.chunkSource = chunkSource;
        }
    }

    private static final class LoadingState {
        private final ServerChunkCache chunkSource;
        private final ChunkMap chunkMap;
        private LinkedHashMap<Long, Integer> routePriorities = new LinkedHashMap<>();
        private List<RouteSlice> routeSlices = List.of();
        private final LinkedHashSet<Long> heldRoutePreloadTickets = new LinkedHashSet<>();
        private final LinkedHashSet<Long> heldRouteFullTickets = new LinkedHashSet<>();
        private final LinkedHashMap<Long, CompletableFuture<?>> scheduledRouteFutures = new LinkedHashMap<>();
        private final LinkedHashSet<Long> loggedFullChunks = new LinkedHashSet<>();
        private long scheduleOrder;
        private long fullOrder;

        private LoadingState(ServerChunkCache chunkSource, ChunkMap chunkMap) {
            this.chunkSource = chunkSource;
            this.chunkMap = chunkMap;
        }

        private void synchronize(LinkedHashMap<Long, Integer> desiredChunks, List<RouteSlice> desiredSlices) {
            if (samePriorities(routePriorities, desiredChunks)) {
                routeSlices = List.copyOf(desiredSlices);
                updateRouteTickets(desiredChunks, fullTicketChunks());
                reprioritizeTargets();
                return;
            }

            for (long chunkKey : routePriorities.keySet()) {
                if (!desiredChunks.containsKey(chunkKey)) {
                    restore(chunkKey);
                }
            }
            routePriorities = new LinkedHashMap<>(desiredChunks);
            routeSlices = List.copyOf(desiredSlices);
            Iterator<Map.Entry<Long, CompletableFuture<?>>> futureIterator =
                    scheduledRouteFutures.entrySet().iterator();
            while (futureIterator.hasNext()) {
                Map.Entry<Long, CompletableFuture<?>> futureEntry = futureIterator.next();
                if (!desiredChunks.containsKey(futureEntry.getKey())) {
                    futureIterator.remove();
                }
            }
            loggedFullChunks.retainAll(desiredChunks.keySet());
            updateRouteTickets(desiredChunks, fullTicketChunks());
            reprioritizeTargets();
            CruiseDebug.debug(ImmersiveAircraftCruise.LOGGER,
                    "[CruiseChunks] route targets synchronized: targets={}, tickets={}",
                    routePriorities.size(),
                    heldRoutePreloadTickets.size() + heldRouteFullTickets.size());
        }

        private LinkedHashSet<Long> fullTicketChunks() {
            LinkedHashSet<Long> result = new LinkedHashSet<>();
            int sliceCount = Math.min(ROUTE_FULL_SLICE_COUNT, routeSlices.size());
            for (int index = 0; index < sliceCount; index++) {
                RouteSlice slice = routeSlices.get(index);
                result.add(slice.center());
                result.add(slice.left());
                result.add(slice.right());
            }
            return result;
        }

        private void updateRouteTickets(LinkedHashMap<Long, Integer> desiredChunks,
                                        LinkedHashSet<Long> desiredFullChunks) {
            DistanceManager distanceManager = ((ServerChunkCacheInvoker) chunkSource)
                    .iacruise$getDistanceManager();
            LinkedHashSet<Long> releasedFullChunks = new LinkedHashSet<>(heldRouteFullTickets);
            releasedFullChunks.removeAll(desiredFullChunks);
            boolean changed = false;
            for (long chunkKey : heldRoutePreloadTickets) {
                if (!desiredChunks.containsKey(chunkKey)) {
                    ChunkPos chunkPos = new ChunkPos(chunkKey);
                    distanceManager.removeTicket(ROUTE_PRELOAD_TICKET,
                            chunkPos, ROUTE_PRELOAD_TICKET_LEVEL, chunkPos);
                    changed = true;
                }
            }
            for (long chunkKey : desiredChunks.keySet()) {
                if (!heldRoutePreloadTickets.contains(chunkKey)) {
                    ChunkPos chunkPos = new ChunkPos(chunkKey);
                    distanceManager.addTicket(ROUTE_PRELOAD_TICKET,
                            chunkPos, ROUTE_PRELOAD_TICKET_LEVEL, chunkPos);
                    changed = true;
                }
            }
            for (long chunkKey : heldRouteFullTickets) {
                if (!desiredFullChunks.contains(chunkKey)) {
                    ChunkPos chunkPos = new ChunkPos(chunkKey);
                    distanceManager.removeTicket(ROUTE_FULL_TICKET,
                            chunkPos, ROUTE_FULL_TICKET_LEVEL, chunkPos);
                    changed = true;
                }
            }
            for (long chunkKey : desiredFullChunks) {
                if (!heldRouteFullTickets.contains(chunkKey)) {
                    ChunkPos chunkPos = new ChunkPos(chunkKey);
                    distanceManager.addTicket(ROUTE_FULL_TICKET,
                            chunkPos, ROUTE_FULL_TICKET_LEVEL, chunkPos);
                    changed = true;
                }
            }
            heldRoutePreloadTickets.retainAll(desiredChunks.keySet());
            heldRoutePreloadTickets.addAll(desiredChunks.keySet());
            heldRouteFullTickets.retainAll(desiredFullChunks);
            heldRouteFullTickets.addAll(desiredFullChunks);
            for (long chunkKey : desiredChunks.keySet()) {
                ChunkHolder holder = findLoadingHolder(chunkMap, chunkKey);
                if (holder != null) {
                    reprioritize(holder);
                }
            }
            if (changed) {
                distanceManager.runAllUpdates(chunkMap);
            }
            // A chunk can stay in the distant preload corridor after leaving
            // the FULL window. Restore its normal queue level after tickets
            // have settled so it no longer competes with gameplay chunks.
            for (long chunkKey : releasedFullChunks) {
                restore(chunkKey);
            }
        }

        private void reprioritizeTargets() {
            for (RouteSlice slice : routeSlices) {
                for (int index = 0; index < RouteSlice.WIDTH; index++) {
                    long chunkKey = slice.chunkKey(index);
                    if (!heldRouteFullTickets.contains(chunkKey)) {
                        continue;
                    }
                    // Submit every route FULL future in path order. The distance ladder
                    // above makes nearer slices leave the sorter before distant slices.
                    scheduleChunk(chunkKey);
                }
            }
        }

        private boolean scheduleChunk(long chunkKey) {
            if (!heldRouteFullTickets.contains(chunkKey)) {
                return true;
            }
            ChunkHolder holder = findLoadingHolder(chunkMap, chunkKey);
            if (holder == null) {
                return false;
            }
            reprioritize(holder);
            if (holder.getFullChunk() != null) {
                scheduledRouteFutures.remove(chunkKey);
                return true;
            }
            CompletableFuture<?> scheduled = scheduledRouteFutures.get(chunkKey);
            if (scheduled != null) {
                if (scheduled.isCancelled() || scheduled.isCompletedExceptionally()) {
                    scheduledRouteFutures.remove(chunkKey);
                } else {
                    return false;
                }
            }
            CompletableFuture<?> future = holder.getOrScheduleFuture(
                    net.minecraft.world.level.chunk.ChunkStatus.FULL, chunkMap);
            scheduledRouteFutures.put(chunkKey, future);
            CruiseDebug.debug(ImmersiveAircraftCruise.LOGGER,
                    "[CruiseChunks] route-schedule order={}, chunk={}, priority={}, queue={}, ticket={}",
                    ++scheduleOrder, holder.getPos(),
                    routePriorities.get(chunkKey),
                    ((ChunkHolderAccessor) holder).iacruise$getQueueLevel(), holder.getTicketLevel());
            return false;
        }

        private void reprioritize(ChunkHolder holder) {
            if (!heldRouteFullTickets.contains(holder.getPos().toLong())) {
                return;
            }
            Integer priority = routePriorities.get(holder.getPos().toLong());
            if (priority != null) {
                changeTaskPriority(chunkMap, holder, priority);
            }
        }

        private void logRouteChunkReady(ChunkHolder holder) {
            long chunkKey = holder.getPos().toLong();
            if (!routePriorities.containsKey(chunkKey)
                    || holder.getFullChunk() == null
                    || !loggedFullChunks.add(chunkKey)) {
                return;
            }
            scheduledRouteFutures.remove(chunkKey);
            CruiseDebug.debug(ImmersiveAircraftCruise.LOGGER,
                    "[CruiseChunks] route-full order={}, chunk={}, priority={}, queue={}, ticket={}",
                    ++fullOrder, holder.getPos(), routePriorities.get(chunkKey),
                    ((ChunkHolderAccessor) holder).iacruise$getQueueLevel(), holder.getTicketLevel());
        }

        private void clear() {
            LinkedHashSet<Long> releasedChunks = new LinkedHashSet<>(routePriorities.keySet());
            routePriorities.clear();
            routeSlices = List.of();
            scheduledRouteFutures.clear();
            loggedFullChunks.clear();
            scheduleOrder = 0L;
            fullOrder = 0L;
            DistanceManager distanceManager = ((ServerChunkCacheInvoker) chunkSource)
                    .iacruise$getDistanceManager();
            for (long chunkKey : heldRoutePreloadTickets) {
                ChunkPos chunkPos = new ChunkPos(chunkKey);
                distanceManager.removeTicket(ROUTE_PRELOAD_TICKET,
                        chunkPos, ROUTE_PRELOAD_TICKET_LEVEL, chunkPos);
            }
            for (long chunkKey : heldRouteFullTickets) {
                ChunkPos chunkPos = new ChunkPos(chunkKey);
                distanceManager.removeTicket(ROUTE_FULL_TICKET,
                        chunkPos, ROUTE_FULL_TICKET_LEVEL, chunkPos);
            }
            if (!heldRoutePreloadTickets.isEmpty() || !heldRouteFullTickets.isEmpty()) {
                distanceManager.runAllUpdates(chunkMap);
            }
            for (long chunkKey : releasedChunks) {
                restore(chunkKey);
            }
            heldRoutePreloadTickets.clear();
            heldRouteFullTickets.clear();
        }

        private void restore(long chunkKey) {
            ChunkHolder holder = findLoadingHolder(chunkMap, chunkKey);
            if (holder != null) {
                changeTaskPriority(chunkMap, holder, holder.getTicketLevel());
            }
        }

        private static boolean samePriorities(Map<Long, Integer> first, Map<Long, Integer> second) {
            if (first.size() != second.size()) {
                return false;
            }
            Iterator<Map.Entry<Long, Integer>> firstIterator = first.entrySet().iterator();
            Iterator<Map.Entry<Long, Integer>> secondIterator = second.entrySet().iterator();
            while (firstIterator.hasNext()) {
                if (!firstIterator.next().equals(secondIterator.next())) {
                    return false;
                }
            }
            return true;
        }
    }

    private static final class PlayerState {
        private final ChunkMap chunkMap;
        private RoutePath priorityPath = RoutePath.empty();
        private final LinkedHashSet<Long> sentChunks = new LinkedHashSet<>();
        private final Map<Long, Long> acknowledgedHashes = new HashMap<>();
        private final Map<Long, Long> pendingHashes = new HashMap<>();
        private final Map<Long, Long> pendingSentTicks = new HashMap<>();
        /** Chunks reported missing/evicted by the client while they remain in the active corridor. */
        private final Set<Long> unreadyChunks = new HashSet<>();
        private final Set<Long> payloadRequestedChunks = new HashSet<>();
        private String cacheNamespace = "";
        private boolean routeCacheEnabled;
        private int routeAnchorChunkX = Integer.MIN_VALUE;
        private int routeAnchorChunkZ = Integer.MIN_VALUE;
        private long routePacketsSent;
        private long routeUnloadsSuppressed;
        private long duplicatePacketsSuppressed;
        private String lastRouteSendBatch = "none";
        private long lastDiagnosticTick = Long.MIN_VALUE;
        private long lastVehicleChunkKey = Long.MIN_VALUE;
        private long lastAnchorChunkKey = Long.MIN_VALUE;
        private boolean lastVehicleLoaded;
        private boolean lastHorizontalCollision;
        private double lastVehicleX;
        private double lastVehicleY;
        private double lastVehicleZ;
        private int stalledVehicleTicks;

        private PlayerState(ChunkMap chunkMap) {
            this.chunkMap = chunkMap;
        }

        private void refreshPriorityPath(NavigationContext navigation) {
            priorityPath = buildRoutePath(navigation, ROUTE_LOOKAHEAD_CHUNKS);
            Set<Long> corridor = priorityPath.corridorChunks();
            sentChunks.retainAll(corridor);
            acknowledgedHashes.keySet().retainAll(corridor);
            pendingHashes.keySet().retainAll(corridor);
            pendingSentTicks.keySet().retainAll(corridor);
            unreadyChunks.retainAll(corridor);
            payloadRequestedChunks.retainAll(corridor);
        }

        private void invalidateChunk(long chunkKey) {
            clearChunkState(chunkKey);
            sentChunks.remove(chunkKey);
            if (priorityPath.corridorChunks().contains(chunkKey)) {
                unreadyChunks.add(chunkKey);
                payloadRequestedChunks.add(chunkKey);
            }
        }

        private void synchronizeRouteCache(ServerPlayer player, NavigationContext navigation) {
            String namespace = CruiseChunkPayloadCache.get(player.serverLevel()).namespace();
            if (!routeCacheEnabled) {
                player.connection.send(new ClientboundSetChunkCacheRadiusPacket(ROUTE_CACHE_RADIUS));
                cacheNamespace = namespace;
                routeCacheEnabled = true;
                CruiseDebug.debug(ImmersiveAircraftCruise.LOGGER,
                        "[CruiseChunks] player {} client cache radius set to {}",
                        player.getScoreboardName(), ROUTE_CACHE_RADIUS);
            }

            ChunkPos center = new ChunkPos(
                    net.minecraft.core.BlockPos.containing(navigation.anchorX(), navigation.anchorY(), navigation.anchorZ()));
            if (routeAnchorChunkX != center.x || routeAnchorChunkZ != center.z) {
                routeAnchorChunkX = center.x;
                routeAnchorChunkZ = center.z;
                CruiseDebug.debug(ImmersiveAircraftCruise.LOGGER,
                        "[CruiseChunks] player {} route preload anchor set to {} / {} "
                                + "(client cache center is maintained locally, serverPlayer={})",
                        player.getScoreboardName(), center.x, center.z, player.chunkPosition());
            }
        }

        private void disableRouteCache(ServerPlayer player) {
            if (routeCacheEnabled) {
                int viewDistance = ((ChunkMapInvoker) chunkMap).iacruise$getViewDistance();
                player.connection.send(new ClientboundSetChunkCacheRadiusPacket(viewDistance));
            }
            priorityPath = RoutePath.empty();
            sentChunks.clear();
            acknowledgedHashes.clear();
            pendingHashes.clear();
            pendingSentTicks.clear();
            unreadyChunks.clear();
            payloadRequestedChunks.clear();
            cacheNamespace = "";
            routeCacheEnabled = false;
            routeAnchorChunkX = Integer.MIN_VALUE;
            routeAnchorChunkZ = Integer.MIN_VALUE;
            routePacketsSent = 0L;
            routeUnloadsSuppressed = 0L;
            duplicatePacketsSuppressed = 0L;
            lastRouteSendBatch = "none";
            lastDiagnosticTick = Long.MIN_VALUE;
            lastVehicleChunkKey = Long.MIN_VALUE;
            lastAnchorChunkKey = Long.MIN_VALUE;
            lastVehicleLoaded = false;
            lastHorizontalCollision = false;
            lastVehicleX = 0.0d;
            lastVehicleY = 0.0d;
            lastVehicleZ = 0.0d;
            stalledVehicleTicks = 0;
        }

        private int sendPriorityChunks(ServerPlayer player, long serverTick) {
            int sentCount = 0;
            StringBuilder sentBatch = new StringBuilder();
            for (RouteSlice slice : priorityPath.slices()) {
                if (sentCount >= ROUTE_PACKETS_PER_SERVER_TICK
                        || pendingHashes.size() >= ROUTE_PACKET_WINDOW) {
                    break;
                }
                LevelChunk center = findFullChunk(chunkMap, slice.center());
                LevelChunk left = findFullChunk(chunkMap, slice.left());
                LevelChunk right = findFullChunk(chunkMap, slice.right());
                // The center line is the aircraft's flight path and must always enter
                // the client stream before either side of the same three-wide slice.
                if (center == null) {
                    break;
                }
                sentCount += sendRouteChunkIfNeeded(player, slice.center(), center, sentBatch, serverTick);
                if (sentCount >= ROUTE_PACKETS_PER_SERVER_TICK
                        || pendingHashes.size() >= ROUTE_PACKET_WINDOW) {
                    break;
                }
                if (left != null) {
                    sentCount += sendRouteChunkIfNeeded(player, slice.left(), left, sentBatch, serverTick);
                }
                if (sentCount >= ROUTE_PACKETS_PER_SERVER_TICK
                        || pendingHashes.size() >= ROUTE_PACKET_WINDOW) {
                    break;
                }
                if (right != null) {
                    sentCount += sendRouteChunkIfNeeded(player, slice.right(), right, sentBatch, serverTick);
                }
                // Preserve a contiguous three-wide prefix: do not let a farther
                // slice overtake a missing chunk in this nearer slice.
                if (left == null || right == null) {
                    break;
                }
            }
            lastRouteSendBatch = sentBatch.length() == 0 ? "none" : sentBatch.toString();
            return sentCount;
        }

        private int sendRouteChunkIfNeeded(ServerPlayer player, long chunkKey, LevelChunk chunk,
                                           StringBuilder sentBatch, long serverTick) {
            // Keep the client snapshot stable while the chunk remains in the active corridor.
            // Block changes invalidate the server cache, but replacing a full client chunk on
            // every change causes a resend/render storm. A missing/evicted ACK clears these
            // entries and requests one fresh full snapshot.
            CruiseChunkPayloadCache.Snapshot snapshot = prepareRouteChunk(this, player, chunk);
            long hash = snapshot.hash();
            Long acknowledged = acknowledgedHashes.get(chunkKey);
            if (acknowledged != null && acknowledged == hash) {
                return 0;
            }
            if (acknowledged != null) {
                acknowledgedHashes.remove(chunkKey);
            }
            Long pending = pendingHashes.get(chunkKey);
            long pendingSince = pendingSentTicks.getOrDefault(chunkKey, Long.MIN_VALUE);
            if (pending != null && pending == hash
                    && serverTick - pendingSince < ROUTE_PACKET_ACK_TIMEOUT_TICKS) {
                return 0;
            }
            if (pending != null) {
                pendingHashes.remove(chunkKey);
                pendingSentTicks.remove(chunkKey);
            }
            payloadRequestedChunks.remove(chunkKey);
            byte[] payload = snapshot.compressedPayload();
            com.g1739.immersiveaircraftcruise.network.CruiseNetwork.CHANNEL.send(
                    net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> player),
                    new com.g1739.immersiveaircraftcruise.network.CruiseRouteChunkPacket(
                            cacheNamespace, player.serverLevel().dimension().location().toString(),
                            chunk.getPos().x, chunk.getPos().z, hash, payload));
            pendingHashes.put(chunkKey, hash);
            pendingSentTicks.put(chunkKey, serverTick);
            sentChunks.add(chunkKey);
            routePacketsSent++;
            if (sentBatch != null) {
                if (sentBatch.length() > 0) {
                    sentBatch.append(',');
                }
                sentBatch.append(ChunkPos.getX(chunkKey)).append('/').append(ChunkPos.getZ(chunkKey))
                        .append(payload.length == 0 ? "[hash]" : "[payload=" + payload.length + "]");
            }
            return 1;
        }

        private void clearChunkState(long chunkKey) {
            acknowledgedHashes.remove(chunkKey);
            pendingHashes.remove(chunkKey);
            pendingSentTicks.remove(chunkKey);
        }

        private void logStatus(ServerPlayer player, NavigationContext navigation,
                               int sentCount, int serverTick) {
            logFlightState(player, navigation);
            if (!CruiseDebug.enabled()) {
                return;
            }
            RouteSlice first = priorityPath.slices().isEmpty() ? null : priorityPath.slices().get(0);
            String firstStatus = first == null
                    ? "none"
                    : describeChunk(first.center()) + "," + describeChunk(first.left()) + "," + describeChunk(first.right());
            String status = "slices=" + priorityPath.slices().size()
                    + ", corridor=" + priorityPath.corridorChunks().size()
                    + ", routeIndex=" + navigation.route().getCurrentIndex()
                    + ", loadingMode=" + navigation.route().getSelectedEntry().loadingMode()
                    + ", loadingStage=" + navigation.route().getLoadingStage()
                    + ", holding=" + navigation.route().isHoldingPattern()
                    + ", routePreloadAnchor=" + routeAnchorChunkX + "/" + routeAnchorChunkZ
                    + ", first=" + (first == null ? "none" : first.center() + "/" + first.left() + "/" + first.right())
                    + ", firstState=" + firstStatus
                    + ", sent=" + sentChunks.size()
                    + ", acknowledged=" + acknowledgedHashes.size()
                    + ", pending=" + pendingHashes.size()
                    + ", unready=" + unreadyChunks.size()
                    + ", payloadRequests=" + payloadRequestedChunks.size()
                    + ", unloadsSuppressed=" + routeUnloadsSuppressed
                    + ", sentNow=" + sentCount
                    + ", sendBatch=" + lastRouteSendBatch;
            if (!status.equals(lastStatus)) {
                CruiseDebug.debug(ImmersiveAircraftCruise.LOGGER,
                        "[CruiseChunks] player {}: {}", player.getScoreboardName(), status);
                lastStatus = status;
            }
            if (lastDiagnosticTick == Long.MIN_VALUE
                    || serverTick - lastDiagnosticTick >= DIAGNOSTIC_INTERVAL_TICKS) {
                lastDiagnosticTick = serverTick;
                CruiseDebug.info(ImmersiveAircraftCruise.LOGGER,
                        "[CruiseChunks] player {} diagnostic: tick={}, playerPos={}, vehiclePos={}, "
                                + "playerChunk={}, vehicleChunk={}, serverView={}, route={}, vehicleHolder={}, "
                                + "velocity={}, horizontalCollision={}, verticalCollision={}, onGround={}, removed={}, "
                                + "stalledTicks={}, routeAnchor={}, anchorSource={}, "
                            + "routePacketsSent={}, unloadsSuppressed={}, duplicatePacketsSuppressed={}, sendBatch={}",
                        player.getScoreboardName(), serverTick,
                        formatPosition(player), formatPosition(navigation.vehicle()),
                        player.chunkPosition(), new ChunkPos(navigation.vehicle().blockPosition()),
                        ((ChunkMapInvoker) chunkMap).iacruise$getViewDistance(),
                        describeRoutePath(player),
                        describeChunk(chunkMap, new ChunkPos(net.minecraft.core.BlockPos.containing(
                                navigation.anchorX(), navigation.anchorY(), navigation.anchorZ())).toLong()),
                        formatVelocity(navigation.vehicle().getDeltaMovement()),
                        navigation.vehicle().horizontalCollision,
                        navigation.vehicle().verticalCollision,
                        navigation.vehicle().onGround(),
                        navigation.vehicle().isRemoved(),
                                stalledVehicleTicks, formatAnchor(navigation), "server-vehicle",
                        routePacketsSent, routeUnloadsSuppressed,
                        duplicatePacketsSuppressed, lastRouteSendBatch);
            }
        }

        private void logFlightState(ServerPlayer player, NavigationContext navigation) {
            VehicleEntity vehicle = navigation.vehicle();
            ChunkPos vehicleChunk = new ChunkPos(vehicle.blockPosition());
            long vehicleChunkKey = vehicleChunk.toLong();
            ChunkPos anchorChunk = new ChunkPos(
                    net.minecraft.core.BlockPos.containing(navigation.anchorX(), navigation.anchorY(), navigation.anchorZ()));
            long anchorChunkKey = anchorChunk.toLong();
            ChunkHolder holder = findLoadingHolder(chunkMap, anchorChunkKey);
            boolean loaded = holder != null && holder.getFullChunk() != null;
            boolean moved = vehicle.getX() != lastVehicleX
                    || vehicle.getY() != lastVehicleY
                    || vehicle.getZ() != lastVehicleZ;
            if (moved) {
                stalledVehicleTicks = 0;
            } else {
                stalledVehicleTicks++;
            }
            boolean changed = vehicleChunkKey != lastVehicleChunkKey
                    || anchorChunkKey != lastAnchorChunkKey
                    || loaded != lastVehicleLoaded
                    || vehicle.horizontalCollision != lastHorizontalCollision
                    || stalledVehicleTicks == 10;
            if (!changed) {
                return;
            }

            if (CruiseDebug.enabled()) {
                CruiseDebug.info(ImmersiveAircraftCruise.LOGGER,
                        "[CruiseChunks] flight-check player {}: serverChunk={}, anchorChunk={}, holder={}, loaded={}, "
                                    + "horizontalCollision={}, verticalCollision={}, onGround={}, pos={}, velocity={}, "
                                    + "serverView={}, stalledTicks={}, routeFirst={}, vehicleType={}, "
                                    + "loadingMode={}, loadingStage={}, trackingRangeChunks={}, pilotDistanceBlocks={}",
                        player.getScoreboardName(), vehicleChunk, anchorChunk,
                        describeChunk(chunkMap, anchorChunkKey), loaded,
                        vehicle.horizontalCollision, vehicle.verticalCollision, vehicle.onGround(),
                        formatPosition(vehicle), formatVelocity(vehicle.getDeltaMovement()),
                        ((ChunkMapInvoker) chunkMap).iacruise$getViewDistance(),
                        stalledVehicleTicks,
                        priorityPath.slices().isEmpty()
                                ? "none"
                                : describeRouteChunk(priorityPath.slices().get(0).center()),
                        vehicle.getType(), navigation.route().getSelectedEntry().loadingMode(),
                        navigation.route().getLoadingStage(), vehicle.getType().clientTrackingRange(),
                        Math.sqrt(player.distanceToSqr(vehicle)));
            }
            if (!loaded || vehicle.horizontalCollision) {
                ImmersiveAircraftCruise.LOGGER.warn(
                        "[CruiseChunks] flight-anomaly player {}: route anchor entered {} with holder={}, "
                                + "loaded={}, horizontalCollision={}, route={}",
                        player.getScoreboardName(), anchorChunk,
                        describeChunk(chunkMap, anchorChunkKey), loaded,
                        vehicle.horizontalCollision, describeRoutePath(player));
            }
            lastVehicleChunkKey = vehicleChunkKey;
            lastAnchorChunkKey = anchorChunkKey;
            lastVehicleLoaded = loaded;
            lastHorizontalCollision = vehicle.horizontalCollision;
            lastVehicleX = vehicle.getX();
            lastVehicleY = vehicle.getY();
            lastVehicleZ = vehicle.getZ();
        }

        private String describeChunk(long chunkKey) {
            return describeChunk(chunkMap, chunkKey);
        }

        private static String describeChunk(ChunkMap chunkMap, long chunkKey) {
            ChunkHolder holder = findLoadingHolder(chunkMap, chunkKey);
            if (holder == null) {
                return "missing";
            }
            ChunkHolderAccessor accessor = (ChunkHolderAccessor) holder;
            return (holder.getFullChunk() == null ? "loading" : "FULL")
                    + ":ticket=" + holder.getTicketLevel()
                    + ":queue=" + accessor.iacruise$getQueueLevel();
        }

        private String describeRoutePath(ServerPlayer player) {
            int full = 0;
            int loading = 0;
            int missing = 0;
            int outsideVanilla = 0;
            int firstBlockedSlice = -1;
            LinkedHashSet<Long> visited = new LinkedHashSet<>();
            LinkedHashMap<Integer, Integer> queueLevels = new LinkedHashMap<>();
            for (int sliceIndex = 0; sliceIndex < priorityPath.slices().size(); sliceIndex++) {
                RouteSlice slice = priorityPath.slices().get(sliceIndex);
                boolean sliceComplete = true;
                for (int index = 0; index < RouteSlice.WIDTH; index++) {
                    long chunkKey = slice.chunkKey(index);
                    if (!visited.add(chunkKey)) {
                        continue;
                    }
                    ChunkHolder holder = findLoadingHolder(chunkMap, chunkKey);
                    if (holder == null) {
                        missing++;
                        sliceComplete = false;
                    } else {
                        if (holder.getFullChunk() == null) {
                            loading++;
                            sliceComplete = false;
                        } else {
                            full++;
                        }
                        ChunkHolderAccessor accessor = (ChunkHolderAccessor) holder;
                        queueLevels.merge(accessor.iacruise$getQueueLevel(), 1, Integer::sum);
                    }
                    if (!isInVanillaView(player, chunkKey)) {
                        outsideVanilla++;
                    }
                }
                if (!sliceComplete && firstBlockedSlice < 0) {
                    firstBlockedSlice = sliceIndex;
                }
            }

            StringBuilder samples = new StringBuilder();
            int[] sampleIndices = {0, 1, 2, 5, 10, 25, 50};
            for (int sampleIndex : sampleIndices) {
                if (sampleIndex >= priorityPath.slices().size()) {
                    continue;
                }
                if (samples.length() > 0) {
                    samples.append(';');
                }
                RouteSlice slice = priorityPath.slices().get(sampleIndex);
                samples.append(sampleIndex).append('[')
                        .append(describeRouteChunk(slice.center()))
                        .append('|').append(describeRouteChunk(slice.left()))
                        .append('|').append(describeRouteChunk(slice.right()))
                        .append(']');
            }
            return "unique=" + visited.size()
                    + ",full=" + full
                    + ",loading=" + loading
                    + ",missing=" + missing
                    + ",outsideServerView=" + outsideVanilla
                    + ",firstBlockedSlice=" + firstBlockedSlice
                    + ",queueLevels=" + queueLevels
                    + ",samples=" + samples;
        }

        private String describeRouteChunk(long chunkKey) {
            return ChunkPos.getX(chunkKey) + "/" + ChunkPos.getZ(chunkKey) + ":" + describeChunk(chunkKey);
        }

        private static String formatPosition(Entity entity) {
            return String.format("%.2f/%.2f/%.2f", entity.getX(), entity.getY(), entity.getZ());
        }

        private static String formatVelocity(Vec3 velocity) {
            return String.format("%.3f/%.3f/%.3f", velocity.x, velocity.y, velocity.z);
        }

        private String lastStatus = "";

        private boolean isInVanillaView(ServerPlayer player, long chunkKey) {
            ChunkPos center = player.chunkPosition();
            return ChunkMap.isChunkInRange(
                    ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey), center.x, center.z,
                    ((ChunkMapInvoker) chunkMap).iacruise$getViewDistance());
        }
    }
}
