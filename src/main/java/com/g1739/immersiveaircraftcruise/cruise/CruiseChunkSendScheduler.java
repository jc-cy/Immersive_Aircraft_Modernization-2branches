package com.g1739.immersiveaircraftcruise.cruise;

import com.g1739.immersiveaircraftcruise.CruiseDebug;
import com.g1739.immersiveaircraftcruise.ImmersiveAircraftCruise;
import com.g1739.immersiveaircraftcruise.mixin.ChunkHolderAccessor;
import com.g1739.immersiveaircraftcruise.mixin.ChunkMapInvoker;
import com.g1739.immersiveaircraftcruise.mixin.ServerChunkCacheInvoker;
import com.g1739.immersiveaircraftcruise.mixin.ServerEntityBroadcast;
import com.g1739.immersiveaircraftcruise.mixin.ServerLevelAccessor;
import com.g1739.immersiveaircraftcruise.mixin.TrackedEntityResync;
import com.g1739.immersiveaircraftcruise.network.CruiseNetwork;
import com.g1739.immersiveaircraftcruise.network.UpdateCruiseRoutePacket;
import immersive_aircraft.entity.VehicleEntity;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTaskPriorityQueueSorter;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityTeleportEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.fml.ModList;
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
import java.util.UUID;
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
    /**
     * Keep the chunk an occupied aircraft sits in loaded, so its terrain is available and the vehicle
     * entity itself is not removed with the chunk.
     *
     * <p>Deliberately not an entity-ticking ticket: entity ticking comes from
     * {@code Entity#isAlwaysTicking} and the client's view of the aircraft comes from
     * {@link #broadcastAircraftState}. Keeping the aircraft inside an entity-ticking chunk cannot be
     * guaranteed at cruise speed - the ticket needs the chunk-status pipeline to catch up while the
     * aircraft crosses a chunk every two to three ticks - and making the whole chunk tick would tick
     * everything else inside it too.
     */
    private static final int ROUTE_ENTITY_CHUNK_TICKET_LEVEL = 33;
    /** The complete route look-ahead must be FULL so the aircraft never outruns the custom stream. */
    private static final int ROUTE_FULL_SLICE_COUNT = ROUTE_LOOKAHEAD_CHUNKS + 1;
    /** Extra acceleration is available once more than ten route chunks are FULL. */
    private static final int ROUTE_ACCELERATION_MIN_FULL_CHUNKS = 10;
    /** A route packet that has not been acknowledged for this long is sent again. */
    private static final long ROUTE_PACKET_ACK_TIMEOUT_TICKS = 80L;
    /** Warn when an occupied aircraft moved this long without a single state broadcast. */
    private static final int BROADCAST_GAP_TICKS = 40;
    private static final double BROADCAST_GAP_MIN_BLOCKS = 1.0d;
    private static final int DIAGNOSTIC_INTERVAL_TICKS = 20;
    /**
     * A cruise aircraft that keeps the same chunk for this long while it still has a leg to fly is
     * stuck: at cruise speed a chunk lasts two to three ticks, and the terrain under it being loaded
     * says nothing about that, so terrain state is deliberately not part of the test.
     */
    private static final int CRUISE_STANDSTILL_TICKS = 60;
    private static final int CRUISE_STANDSTILL_CHECK_INTERVAL_TICKS = 5;
    /** Inside this range of the final target the aircraft legitimately slows, hovers and lands. */
    private static final double CRUISE_STANDSTILL_MIN_TARGET_DISTANCE = 64.0d;
    /**
     * The standstill window is only kept while the aircraft is not moving at all: taking off and
     * climbing without crossing a chunk is movement, a frozen copy is not.
     */
    private static final double CRUISE_STANDSTILL_MIN_MOVE_BLOCKS = 1.0d;
    private static final String ENTITY_TICK_MESSAGE =
            "[CruiseEntityTick] serverTick={}, vehicleId={}, vehicleTick={}, stalledTicks={}, chunk={}, reason={}, "
                    + "alwaysTicking={}, entityTickList={}, entityTickingRange={}, holderStatus={}, "
                    + "holderTicketLevel={}, entityTicketOwned={}, removed={}, passengers={}, pilot={}";
    private static final TicketType<ChunkPos> ROUTE_PRELOAD_TICKET = TicketType.create(
            "iacruise_route_preload", Comparator.comparingLong(ChunkPos::toLong));
    private static final TicketType<ChunkPos> ROUTE_FULL_TICKET = TicketType.create(
            "iacruise_route_full", Comparator.comparingLong(ChunkPos::toLong));
    private static final TicketType<Integer> ROUTE_ENTITY_CHUNK_TICKET = TicketType.create(
            "iacruise_route_entity_chunk", Comparator.comparingInt(Integer::intValue));
    private static final Map<ServerPlayer, PlayerState> NETWORK_STATES = new IdentityHashMap<>();
    private static final Map<ServerPlayer, String> CONTEXT_STATUSES = new IdentityHashMap<>();
    private static final Map<ServerPlayer, PendingTeleport> PENDING_TELEPORTS = new IdentityHashMap<>();
    private static final Map<ChunkMap, LoadingState> LOADING_STATES = new IdentityHashMap<>();
    private static final Map<VehicleEntity, EntityChunkTicketState> ENTITY_CHUNK_TICKETS = new IdentityHashMap<>();
    private static final Map<VehicleEntity, EntityTickDiagnostic> ENTITY_TICK_DIAGNOSTICS = new IdentityHashMap<>();
    /** Last tick on which the aircraft's position/passenger packets actually went out. */
    private static final Map<VehicleEntity, BroadcastMark> LAST_BROADCAST = new IdentityHashMap<>();
    /** Who rode which aircraft, so a same-dimension chunk reload can put them back. */
    private static final Map<UUID, RideMemory> RIDE_MEMORY = new HashMap<>();
    private static final long RIDE_MEMORY_TTL_TICKS = 20L * 60L * 5L;
    private static final long RIDE_RESYNC_COOLDOWN_TICKS = 20L;
    private static final Map<ServerPlayer, Long> RIDE_RESYNC_COOLDOWNS = new IdentityHashMap<>();
    /**
     * The reset key has its own clock. The automatic drift check repairs every couple of seconds, and a
     * press that shared that clock would be swallowed by it - the "the key does nothing" report.
     */
    private static final long HARD_RESET_COOLDOWN_TICKS = 40L;
    private static final Map<ServerPlayer, Long> HARD_RESET_COOLDOWNS = new IdentityHashMap<>();
    private static final Map<ServerLevel, Set<Long>> CACHE_INVALIDATIONS = new ConcurrentHashMap<>();
    /** Last chunk each cruising aircraft held, for the standstill check. */
    private static final Map<VehicleEntity, StandstillMark> STANDSTILL_MARKS = new IdentityHashMap<>();
    /**
     * How many standstill verdicts in a row an aircraft has earned. One verdict is already three
     * seconds without movement, but a server that stalls under load can produce one for a healthy
     * aircraft, so the control seat is only rebuilt once a second verdict confirms it.
     */
    private static final Map<VehicleEntity, Integer> STANDSTILL_REPORTS = new IdentityHashMap<>();
    /**
     * Aircraft that have already flown in this navigation run. Standing still before ever moving is a
     * take-off waiting for its pilot, not a freeze, so the standstill check only arms after the aircraft
     * has moved once.
     */
    private static final Set<VehicleEntity> HAS_FLOWN =
            java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    /**
     * Mods that are known to transform the same vanilla methods this mod hooks. The installed mod list is
     * visible in the launcher already; what is worth logging is which of them may have changed a hook
     * this mod depends on, so the line is written once, at the first anomaly.
     */
    private static final String[][] WATCHED_HOOK_OWNERS = {
            {"radium", "ChunkMap/DistanceManager/ServerLevel/Entity"},
            {"modernfix", "ChunkMap/ServerLevel/ServerGamePacketListenerImpl/LevelChunk/Entity"},
            {"byepregen", "ServerLevel/ServerChunkCache/LevelChunk"},
            {"collections_of_optimizations", "ChunkMap/ServerLevel/ServerGamePacketListenerImpl/PlayerList"},
            {"randomoptimization", "ServerChunkCache/ServerLevel"},
            {"ruokmod", "ClientChunkCache/LevelChunk/Entity"},
            {"stellarcreateoptimization", "ServerLevel/LevelChunk/Entity"},
            {"chloride", "Entity"},
            {"memoryleakfix", "ServerLevel/Entity"},
            {"fastsuite", "ServerLevel/MinecraftServer"},
            {"mbd2thread", "ServerLevel/ServerPlayer/PlayerList"},
            {"embeddium", "LevelChunk/Entity"},
            {"entityculling", "LevelChunk/Entity"},
            {"immersive_optimization", "ServerLevel(entity ticking)"},
    };
    private static boolean anomalyReported;

    private CruiseChunkSendScheduler() {
    }

    public static void register() {
        MinecraftForge.EVENT_BUS.addListener(CruiseChunkSendScheduler::onServerTick);
        MinecraftForge.EVENT_BUS.addListener(CruiseChunkSendScheduler::onEntityTeleport);
        MinecraftForge.EVENT_BUS.addListener(CruiseChunkSendScheduler::onPlayerChangedDimension);
        MinecraftForge.EVENT_BUS.addListener(CruiseChunkSendScheduler::onPlayerLoggedIn);
        MinecraftForge.EVENT_BUS.addListener(CruiseChunkSendScheduler::onPlayerLoggedOut);
        MinecraftForge.EVENT_BUS.addListener(CruiseChunkSendScheduler::onServerStopped);
        MinecraftForge.EVENT_BUS.addListener(CruiseChunkSendScheduler::onEntityJoinLevel);
        CruiseDebug.info(ImmersiveAircraftCruise.LOGGER, "[CruiseChunks] scheduler registered");
    }

    /**
     * Single exit for every change of who rides a cruise aircraft.
     *
     * <p>Called from the {@code Entity} mixin on mount, dismount and vehicle removal. It records the
     * ride for a later same-dimension restore and pushes the authoritative passenger list straight to
     * the affected player, so a teleport, a spectator switch, the chunk system unloading the vehicle
     * or IA's own "kick the first passenger" can no longer leave a client riding an aircraft the
     * server has already dismounted it from.
     */
    public static void onRidingStateChanged(VehicleEntity vehicle, ServerPlayer player, boolean mounted) {
        Set<UUID> passengers = orderedRidePassengers(vehicle.getPassengers());
        if (mounted) {
            passengers.add(player.getUUID());
        } else {
            passengers.remove(player.getUUID());
        }
        RIDE_MEMORY.put(vehicle.getUUID(),
                new RideMemory(vehicle.level().dimension(), passengers, player.server.getTickCount()));
        if (mounted) {
            // Boarding is the moment a rider must end up with the aircraft's current route and upgrades;
            // nothing is guessed from what the client happened to track before. The list already names
            // this rider, so it is correct to broadcast it here.
            sendPassengerList(vehicle);
            sendAuthoritativeRoute(player, vehicle);
        } else {
            // The passenger list is broadcast from onPassengerListChanged once the leaver has really left
            // the vehicle's list. Sending it from here would still name them as the pilot, and the
            // remaining rider would keep a client that hands the controls to the player who left.
            // Vanilla needs nothing here because a dismounting client and the server already agree on the
            // rider's position. Cruise flight is the exception: the client's own copy of the aircraft can
            // be tens of blocks from the server's (client-predicted pilot, interpolation, stalls), so the
            // authoritative position is handed over explicitly instead of letting the next move packet be
            // corrected - which would look like an unexplained pull-back right after stepping off.
            player.connection.teleport(player.getX(), player.getY(), player.getZ(),
                    player.getYRot(), player.getXRot());
        }
        ImmersiveAircraftCruise.LOGGER.info(
                "[CruiseRide] player={} {} vehicleId={}, passengers={}, tick={}",
                player.getScoreboardName(), mounted ? "boarded" : "left",
                vehicle.getId(), passengers.size(), player.server.getTickCount());
    }

    /**
     * Pushes the passenger list after a rider left the aircraft.
     *
     * <p>The list is the control seat: {@code getFirstPassenger} is the pilot on every client, so it has
     * to be broadcast once the departing rider has really left the vehicle's list. A packet built while
     * they are still in it keeps naming them as the pilot, and the remaining rider's client then refuses
     * the local player's input while the server already counts that rider as the pilot - the state where
     * the passenger sits in a motionless aircraft and the refresh key has nothing to correct. The leaver
     * is included as well, so their own client learns it is no longer aboard.
     */
    public static void onPassengerListChanged(VehicleEntity vehicle, ServerPlayer leaver) {
        ClientboundSetPassengersPacket packet = new ClientboundSetPassengersPacket(vehicle);
        for (Entity passenger : vehicle.getPassengers()) {
            if (passenger instanceof ServerPlayer rider) {
                rider.connection.send(packet);
            }
        }
        if (leaver != null && !leaver.hasDisconnected()) {
            leaver.connection.send(packet);
        }
    }

    /**
     * Rider order is control ownership: {@code getFirstPassenger} is the pilot on both sides, so a
     * remembered ride has to keep the server's own passenger order. A hash-ordered set would let a
     * restore hand the aircraft to a different player, which is exactly the "passenger became the
     * pilot" split.
     */
    private static Set<UUID> orderedRidePassengers(List<Entity> passengers) {
        Set<UUID> ordered = new LinkedHashSet<>();
        for (Entity passenger : passengers) {
            if (passenger instanceof ServerPlayer rider) {
                ordered.add(rider.getUUID());
            }
        }
        return ordered;
    }

    /**
     * Pushes the authoritative passenger list to every rider currently on the aircraft.
     *
     * <p>The list is not decoration: {@code Entity#getFirstPassenger} is what decides who controls an
     * aircraft on each client, and a client that still counts itself as the controller ignores the
     * server's position updates for that aircraft entirely. Telling only the player whose own ride
     * changed therefore leaves every other rider with a stale controller.
     *
     * <p>Only call this while {@code vehicle.getPassengers()} already is the list that should go out:
     * boarding and the on-demand repair do. A rider leaving is broadcast by
     * {@link #onPassengerListChanged} after the removal instead, because during the removal the leaver
     * is still in the list and would keep being named as the pilot.
     */
    private static void sendPassengerList(VehicleEntity vehicle) {
        ClientboundSetPassengersPacket packet = new ClientboundSetPassengersPacket(vehicle);
        for (Entity passenger : vehicle.getPassengers()) {
            if (passenger instanceof ServerPlayer rider) {
                rider.connection.send(packet);
            }
        }
    }

    /**
     * Repairs a runtime riding state reported by the client heartbeat check or the manual cruise key.
     *
     * <p>This is the only client-driven path left, and it is a validation against information the
     * server already broadcasts every five ticks rather than a heuristic: the heartbeat names the
     * aircraft the server counts the player on. Three outcomes, all resolved from server state:
     *
     * <ul>
     *   <li>the server counts the player on that same aircraft - the client's own copy of it is what
     *       is stale, so it is rebuilt from server state ({@link #rebuildAircraftView});</li>
     *   <li>the server counts the player on another aircraft - that aircraft's view is rebuilt for
     *       this client as well;</li>
     *   <li>the server counts the player on nothing, but session memory still lists them on a loaded
     *       aircraft in this dimension - they are put back on the seat.</li>
     * </ul>
     *
     * <p>Anything else is ignored, and every outcome is rate limited per player.
     */
    public static void repairRideState(ServerPlayer player, int vehicleId) {
        if (player == null || player.hasDisconnected()) {
            return;
        }
        long serverTick = player.server.getTickCount();
        Long previous = RIDE_RESYNC_COOLDOWNS.get(player);
        if (previous != null && serverTick - previous < RIDE_RESYNC_COOLDOWN_TICKS) {
            return;
        }
        RIDE_RESYNC_COOLDOWNS.put(player, serverTick);
        // The client sends the aircraft it believes it is riding (0 when it believes it rides none).
        // A riding client validates every five-tick heartbeat; the cruise key validates manually.
        Entity root = player.getRootVehicle();
        if (root instanceof VehicleEntity onboard && CruiseController.hasCruiseModule(onboard)) {
            if (onboard.getId() != vehicleId) {
                ImmersiveAircraftCruise.LOGGER.warn(
                        "[CruiseRide] client reported {} while riding {}: player={}",
                        vehicleId, onboard.getId(), player.getScoreboardName());
            }
            rebuildAircraftView(player, onboard);
            return;
        }
        VehicleEntity remembered = restoreRememberedRide(player);
        if (remembered == null) {
            return;
        }
        rebuildAircraftView(player, remembered);
        ImmersiveAircraftCruise.LOGGER.warn(
                "[CruiseRide] put a player back on the aircraft the client still rode: player={}, vehicleId={}",
                player.getScoreboardName(), remembered.getId());
    }

    /**
     * The reset key's strong repair: it always acts, and on the riding side it rebuilds this client's
     * copy of the aircraft instead of only aligning data with it.
     *
     * <p>The automatic drift check stays data-only on purpose - it runs by itself every couple of
     * seconds and entity churn there would be stutter the player never asked for. A key press is the
     * opposite: the player is already stuck and wants the one repair that can still work, so the copy
     * is thrown away and re-created by the vanilla pairing path. A spawn packet is the only thing that
     * ever places an entity in a client that no longer ticks it, which is exactly the state a frozen
     * copy is in.
     *
     * <p>It never refuses on a technicality: no ride on the server but one in session memory means the
     * player is put back on it first; a client that rides an aircraft this server has no ride for is
     * sent that aircraft's authoritative passenger list, which does not name it, so the client lets its
     * own copy go.
     *
     * <p>Whoever asks for this version gets the rebuild, the control seat included: it is the rescue
     * path, and a player who presses it (or a client that has watched its copy stay broken for ten
     * seconds, or an aircraft the server has proven is not moving) is already past the point where
     * "nothing changed" is acceptable. The cost is the one a rebuild always has for the control seat -
     * that client's copy is re-created, so it is dismounted and re-seated once and its local flight
     * state restarts from the server's - which is why the automatic drift check still prefers the cheap
     * alignment and only escalates after ten seconds.
     *
     * <p>{@code clientControls} is not a filter: it is logged against the server's own view, because
     * "the pilot left and the seat moved to another player while some client kept flying the old
     * arrangement" is exactly the split that used to be mistaken for a healthy control seat.
     */
    public static void hardResetRideState(ServerPlayer player, int reportedVehicleId,
                                          boolean clientControls) {
        hardResetRideState(player, reportedVehicleId, Boolean.valueOf(clientControls));
    }

    /**
     * The reset path used by the server's own detectors (for example the cruise standstill check).
     * There is no client report to compare against on that path, so the log records
     * {@code controlSeatSplit=n/a} instead of comparing the server's verdict with itself.
     */
    public static void hardResetRideState(ServerPlayer player, int reportedVehicleId) {
        hardResetRideState(player, reportedVehicleId, null);
    }

    private static void hardResetRideState(ServerPlayer player, int reportedVehicleId,
                                           Boolean clientControls) {
        if (player == null || player.hasDisconnected()) {
            return;
        }
        long serverTick = player.server.getTickCount();
        Long previous = HARD_RESET_COOLDOWNS.get(player);
        if (previous != null && serverTick - previous < HARD_RESET_COOLDOWN_TICKS) {
            return;
        }
        HARD_RESET_COOLDOWNS.put(player, serverTick);

        VehicleEntity vehicle = null;
        boolean restored = false;
        if (player.getRootVehicle() instanceof VehicleEntity onboard
                && CruiseController.hasCruiseModule(onboard)) {
            vehicle = onboard;
        } else {
            VehicleEntity remembered = restoreRememberedRide(player);
            if (remembered != null) {
                vehicle = remembered;
                restored = true;
            }
        }

        if (vehicle == null) {
            convergeGhostRide(player, reportedVehicleId);
            return;
        }

        // Align the server-side seat first: the re-pair below is a vanilla tracking call, and vanilla
        // only pairs a player that is within its tracking range of the entity.
        CruiseController.synchronizeCruiseMovement(vehicle, "hard-reset");
        boolean serverControls = vehicle.getControllingPassenger() == player;
        boolean rebuilt = forceRebuildPairing(player, vehicle);
        if (!rebuilt) {
            // Also corrects the control seat when this client had the list wrong, and covers the case
            // where the pairing could not be re-created because the player is out of tracking range.
            player.connection.send(new ClientboundSetPassengersPacket(vehicle));
        }
        sendAuthoritativeRoute(player, vehicle);
        ImmersiveAircraftCruise.LOGGER.warn(
                "[CruiseRide] hard reset: player={}, vehicleId={}, rebuilt={}, restoredRide={}, "
                        + "serverControlSeat={}, controlSeatSplit={}, riders={}",
                player.getScoreboardName(), vehicle.getId(), rebuilt, restored,
                serverControls,
                clientControls == null ? "n/a" : Boolean.toString(serverControls != clientControls),
                vehicle.getPassengers().size());
    }

    /**
     * The client rides an aircraft this server keeps no ride for, and session memory has nothing either
     * (another dimension, a destroyed aircraft, or a copy that outlived its pairing). Nothing can be
     * rebuilt, but the client is still brought back to the server's own truth: the aircraft's
     * authoritative passenger list does not name the player, so their client releases the copy, and
     * their position is re-sent from the server's side.
     */
    private static void convergeGhostRide(ServerPlayer player, int reportedVehicleId) {
        Entity claimed = player.serverLevel().getEntity(reportedVehicleId);
        if (claimed instanceof VehicleEntity vehicle && CruiseController.hasCruiseModule(vehicle)) {
            pairAircraftWith(player, vehicle);
            player.connection.send(new ClientboundSetPassengersPacket(vehicle));
            ImmersiveAircraftCruise.LOGGER.warn(
                    "[CruiseRide] hard reset released a client-side ride the server does not keep: "
                            + "player={}, vehicleId={}",
                    player.getScoreboardName(), vehicle.getId());
        } else {
            ImmersiveAircraftCruise.LOGGER.warn(
                    "[CruiseRide] hard reset found no aircraft to repair: player={}, reportedVehicleId={}",
                    player.getScoreboardName(), reportedVehicleId);
        }
        player.connection.teleport(player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot());
    }

    /**
     * Drops this client's copy of the aircraft and re-creates it from the authoritative entity: the
     * removal makes the client let go of the entity it rides, the re-pair sends the spawn packet (with
     * the entity data and the server's seat order) that builds a fresh copy at the authoritative
     * position.
     *
     * <p>Vanilla's re-pair keeps its own range check, so the result is verified; if it declined (the
     * player happens to be outside the tracking range of the aircraft), the pairing is restored by
     * hand. That check exists to stop wasting bandwidth on entities a player cannot see, which is not
     * a reason to leave a player who asked for a reset without their aircraft.
     */
    /**
     * The vanilla tracking entry of this aircraft, or {@code null} when it is not tracked right now.
     * Both "complete the pairing" and "rebuild the pairing" need exactly this lookup, so it lives here.
     */
    private static TrackedEntityResync trackedEntity(VehicleEntity vehicle) {
        ServerLevel level = (ServerLevel) vehicle.level();
        Object tracked = ((ChunkMapInvoker) level.getChunkSource().chunkMap)
                .iacruise$getEntityMap().get(vehicle.getId());
        return tracked instanceof TrackedEntityResync resync ? resync : null;
    }
    private static boolean forceRebuildPairing(ServerPlayer player, VehicleEntity vehicle) {
        TrackedEntityResync resync = trackedEntity(vehicle);
        if (resync == null) {
            return false;
        }
        resync.iacruise$removePlayer(player);
        resync.iacruise$updatePlayer(player);
        if (!resync.iacruise$getSeenBy().contains(player.connection)) {
            resync.iacruise$getSeenBy().add(player.connection);
            if (resync.iacruise$getServerEntity() instanceof ServerEntityBroadcast broadcast) {
                broadcast.iacruise$addPairing(player);
            }
        }
        return true;
    }

    /**
     * Re-sends the authoritative state of an aircraft the server already counts this player as riding.
     *
     * <p>Nothing is deleted. Dropping the pairing would make the client discard the entity it is riding,
     * and a removed vehicle ejects its passenger on that client while the server keeps them seated - the
     * split that turns a lagging copy into "each side has its own aircraft". The client's copy is
     * corrected in place instead:
     *
     * <ul>
     *   <li>pairing is only completed when the client does not have the entity yet (no-op otherwise),
     *       and the pairing bundle carries the spawn packet, the entity data and the passenger list;</li>
     *   <li>the passenger list repeats the server's seat order, which is what decides who controls the
     *       aircraft on every client;</li>
     *   <li>riders other than the controller are pulled to the authoritative position by the vanilla
     *       position packet, which a client only applies to entities it does not control - the
     *       controlling client stays where its own authoritative copy is;</li>
     *   <li>the server-side half (riders follow the aircraft, chunk tracking centre follows them) runs
     *       through the same movement transaction the pilot's move packets use.</li>
     * </ul>
     */
    private static void rebuildAircraftView(ServerPlayer player, VehicleEntity vehicle) {
        reportHookOwners("ride repair");
        pairAircraftWith(player, vehicle);
        sendPassengerList(vehicle);
        // The route lives on the authoritative entity, so it travels with every repair; otherwise a
        // repair would leave the client showing the navigation it just started as stopped.
        sendAuthoritativeRoute(player, vehicle);
        if (vehicle.getControllingPassenger() != player) {
            player.connection.send(new ClientboundTeleportEntityPacket(vehicle));
        }
        CruiseController.synchronizeCruiseMovement(vehicle, "ride-repair");
        ImmersiveAircraftCruise.LOGGER.info(
                "[CruiseRide] re-sent aircraft state: player={}, vehicleId={}, riders={}",
                player.getScoreboardName(), vehicle.getId(), vehicle.getPassengers().size());
    }

    /**
     * Logs, once per session and only when an anomaly happens, which installed mods are known to
     * transform hooks this mod depends on. The installed mod list is visible in the launcher; what this
     * adds is which of them may have changed one of those hooks, which is what an A/B run needs.
     */
    private static void reportHookOwners(String trigger) {
        if (anomalyReported) {
            return;
        }
        anomalyReported = true;
        List<String> present = new ArrayList<>();
        for (String[] entry : WATCHED_HOOK_OWNERS) {
            if (ModList.get().isLoaded(entry[0])) {
                present.add(entry[0] + "(" + entry[1] + ")");
            }
        }
        if (!present.isEmpty()) {
            ImmersiveAircraftCruise.LOGGER.warn(
                    "[CruiseDiag] {}: installed mods that patch hooks this mod relies on: {}",
                    trigger, String.join(", ", present));
        }
    }

    /**
     * Makes sure this client knows the aircraft entity, without ever dropping a pairing. The pairing
     * bundle carries the spawn packet, the entity data and the passenger list together, and completing
     * it is a no-op for a client that already tracks the aircraft.
     */
    private static void pairAircraftWith(ServerPlayer player, VehicleEntity vehicle) {
        TrackedEntityResync resync = trackedEntity(vehicle);
        if (resync != null) {
            resync.iacruise$updatePlayer(player);
        }
    }

    /** Finds the aircraft the session memory still lists this player as riding, same dimension. */
    private static VehicleEntity rememberedVehicle(ServerPlayer player) {
        for (Map.Entry<UUID, RideMemory> entry : RIDE_MEMORY.entrySet()) {
            RideMemory memory = entry.getValue();
            if (!memory.passengerIds().contains(player.getUUID())
                    || !player.serverLevel().dimension().equals(memory.dimension())) {
                continue;
            }
            if (player.serverLevel().getEntity(entry.getKey()) instanceof VehicleEntity vehicle
                    && CruiseController.hasCruiseModule(vehicle)) {
                return vehicle;
            }
        }
        return null;
    }

    /**
     * Puts the player back on the aircraft this session still remembers it riding, when that ride can
     * still be restored: the vehicle must exist in this dimension (that is what {@code rememberedVehicle}
     * checks), the player must be alive, and vanilla has to accept the seat.
     *
     * <p>Both repair paths share this step, so the rule for "restoring a remembered ride" lives in one
     * place; each caller decides what to do with the aircraft it gets back.
     */
    private static VehicleEntity restoreRememberedRide(ServerPlayer player) {
        VehicleEntity remembered = rememberedVehicle(player);
        if (remembered == null || player.isDeadOrDying() || !player.startRiding(remembered, true)) {
            return null;
        }
        remembered.positionRider(player);
        return remembered;
    }
    /** Captures the current ride before the chunk system removes the vehicle and ejects everyone. */
    public static void rememberRide(VehicleEntity vehicle) {
        Set<UUID> passengers = orderedRidePassengers(vehicle.getPassengers());
        if (passengers.isEmpty()) {
            return;
        }
        MinecraftServer server = vehicle.getServer();
        RIDE_MEMORY.put(vehicle.getUUID(), new RideMemory(vehicle.level().dimension(), passengers,
                server == null ? 0L : server.getTickCount()));
    }

    /**
     * Tells every rider's client that the aircraft itself is going away, right before the chunk system
     * removes it, and aligns their position in the same tick.
     *
     * <p>Vanilla ejects the riders on the server a moment later and normally announces the removal
     * through entity tracking, but a client that missed that packet (a stall, a reconnect, a dropped
     * pairing) keeps riding a copy of an aircraft that no longer exists - and by then the server has
     * nothing left to correct, so every request about it is dropped. Removing the entity explicitly is
     * the vanilla way for a client to let go: it drops the copy, ejects the local player and stops
     * riding.
     *
     * <p>This is not the hand-over path: the aircraft is gone, so there is no control seat to hand over.
     * If the same aircraft loads again, {@code onEntityJoinLevel} restores the ride from the memory taken
     * just before the removal.
     */
    public static void releaseRidersOnRemoval(VehicleEntity vehicle) {
        ClientboundRemoveEntitiesPacket removal = new ClientboundRemoveEntitiesPacket(vehicle.getId());
        for (Entity passenger : List.copyOf(vehicle.getPassengers())) {
            if (passenger instanceof ServerPlayer rider) {
                rider.connection.send(removal);
                rider.connection.teleport(rider.getX(), rider.getY(), rider.getZ(),
                        rider.getYRot(), rider.getXRot());
            }
        }
    }

    /**
     * Restores a ride when the same entity is loaded again in the same dimension: only players who
     * are still online, in that dimension and next to the aircraft are put back. Nothing is stored
     * across server sessions, so re-joining keeps vanilla behaviour.
     */
    private static void onEntityJoinLevel(net.minecraftforge.event.entity.EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof VehicleEntity vehicle)
                || !CruiseController.hasCruiseModule(vehicle)) {
            return;
        }
        RideMemory memory = RIDE_MEMORY.get(vehicle.getUUID());
        if (memory == null || memory.passengerIds().isEmpty()
                || !event.getLevel().dimension().equals(memory.dimension())) {
            return;
        }
        MinecraftServer server = vehicle.getServer();
        if (server == null) {
            return;
        }
        if (server.getTickCount() - memory.tick() > RIDE_MEMORY_TTL_TICKS) {
            RIDE_MEMORY.remove(vehicle.getUUID());
            return;
        }
        for (UUID id : memory.passengerIds()) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            // The ride is only remembered for players whose ride was ended collaterally by a chunk
            // unload, so no distance test is needed: a passenger whose position sync lagged behind
            // must still be put back. Riding something else or being dead are the only real conflicts.
            if (player == null || player.level() != event.getLevel()
                    || player.isDeadOrDying() || player.getRootVehicle() != player) {
                continue;
            }
            if (player.startRiding(vehicle, true)) {
                vehicle.positionRider(player);
                player.serverLevel().getChunkSource().move(player);
                // The seat exists on this client only once the entity does; complete the pairing before
                // the next packets, so the passenger list can never describe an aircraft the client has
                // not been told about yet.
                pairAircraftWith(player, vehicle);
                ImmersiveAircraftCruise.LOGGER.info(
                        "[CruiseRide] restored ride after the aircraft was loaded again: "
                                + "player={}, vehicleUuid={}",
                        player.getScoreboardName(), vehicle.getUUID());
            }
        }
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

    /** Keeps the current chunk of an actively preloading aircraft loaded while it flies. */
    static void updateEntityChunkTicket(VehicleEntity vehicle) {
        EntityChunkTicketState previous = ENTITY_CHUNK_TICKETS.get(vehicle);
        if (!requiresEntityChunkTicket(vehicle)) {
            if (previous != null) {
                releaseEntityChunkTicket(vehicle, previous);
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
                    removeEntityChunkTicket(previousDistanceManager, new ChunkPos(chunkKey), previous.vehicleId());
                }
            }
        }
        for (long chunkKey : desiredChunks) {
            if (previous == null || previousSourceChanged || !previous.chunkKeys().contains(chunkKey)) {
                ChunkPos chunkPos = new ChunkPos(chunkKey);
                distanceManager.addTicket(ROUTE_ENTITY_CHUNK_TICKET, chunkPos,
                        ROUTE_ENTITY_CHUNK_TICKET_LEVEL, vehicle.getId());
            }
        }
        ENTITY_CHUNK_TICKETS.put(vehicle, new EntityChunkTicketState(chunkSource, desiredChunks, vehicle.getId()));
        if (previousSourceChanged) {
            DistanceManager previousDistanceManager = ((ServerChunkCacheInvoker) previous.chunkSource())
                    .iacruise$getDistanceManager();
            previousDistanceManager.runAllUpdates(previous.chunkSource().chunkMap);
        }
        distanceManager.runAllUpdates(chunkMap);
    }

    /**
     * Asks vanilla to broadcast this aircraft's position and passenger list every server tick.
     *
     * <p>{@code ChunkMap} only runs {@code ServerEntity#sendChanges} when the entity's section changed
     * or its chunk is in the entity-ticking range, and the second half is a race the aircraft wins:
     * the ticket needs the chunk-status pipeline to catch up while a cruise aircraft crosses a chunk
     * every few ticks. Calling the same broadcast function directly makes the client's view
     * independent of that race, and it costs less than turning the whole chunk entity-ticking.
     */
    private static void broadcastAircraftState(VehicleEntity vehicle) {
        if (vehicle.level().isClientSide() || !requiresEntityChunkTicket(vehicle)) {
            return;
        }
        ServerChunkCache chunkSource = ((ServerLevel) vehicle.level()).getChunkSource();
        Object tracked = ((ChunkMapInvoker) chunkSource.chunkMap)
                .iacruise$getEntityMap().get(vehicle.getId());
        if (tracked instanceof TrackedEntityResync access
                && access.iacruise$getServerEntity() instanceof ServerEntityBroadcast broadcast) {
            broadcast.iacruise$sendChanges();
        }
    }

    /**
     * Called from the tracking broadcast itself, so the diagnostics see the aircraft's state actually
     * leaving the server instead of only trusting the condition that should have allowed it.
     */
    public static void noteBroadcast(Entity entity) {
        if (entity instanceof VehicleEntity vehicle
                && !vehicle.level().isClientSide()
                && CruiseController.hasCruiseModule(vehicle)) {
            LAST_BROADCAST.put(vehicle, new BroadcastMark(vehicle.tickCount,
                    vehicle.getX(), vehicle.getY(), vehicle.getZ()));
        }
    }

    private static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        applyPendingTeleports(event.getServer());
        processCacheInvalidations();
        Map<ServerPlayer, NavigationContext> activeByPlayer = activeNavigationContexts(event.getServer());
        updateEntityChunkTickets(event.getServer(), activeByPlayer);
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

    private static void updateEntityChunkTickets(MinecraftServer server,
                                                 Map<ServerPlayer, NavigationContext> activeByPlayer) {
        Set<VehicleEntity> activeVehicles = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (NavigationContext navigation : activeByPlayer.values()) {
            activeVehicles.add(navigation.vehicle());
        }
        // Occupied aircraft keep their ticket even when navigation is not running (for example right
        // after a collision stopped it); releasing it there is what let the chunk - and the vehicle
        // entity with its passengers - unload.
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.getRootVehicle() instanceof VehicleEntity vehicle) {
                activeVehicles.add(vehicle);
                if (CruiseController.hasCruiseModule(vehicle)) {
                    // Vanilla moves a rider's chunk-tracking centre only for the vehicle controller
                    // (handleMoveVehicle); a passenger's centre otherwise stays where they boarded, so
                    // at cruise speed their client view window ends up hundreds of blocks behind the
                    // aircraft and every chunk packet for the area it flies over is refused.
                    player.serverLevel().getChunkSource().move(player);
                }
            }
        }
        for (VehicleEntity vehicle : activeVehicles) {
            updateEntityChunkTicket(vehicle);
            broadcastAircraftState(vehicle);
        }

        Iterator<Map.Entry<VehicleEntity, EntityChunkTicketState>> iterator = ENTITY_CHUNK_TICKETS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<VehicleEntity, EntityChunkTicketState> entry = iterator.next();
            if (requiresEntityChunkTicket(entry.getKey())) {
                continue;
            }
            EntityChunkTicketState state = entry.getValue();
            DistanceManager distanceManager = ((ServerChunkCacheInvoker) state.chunkSource())
                    .iacruise$getDistanceManager();
            removeEntityChunkTicket(distanceManager, state);
            distanceManager.runAllUpdates(state.chunkSource().chunkMap);
            iterator.remove();
        }
    }

    private static void logEntityTickDiagnostics(MinecraftServer server,
                                                 Map<ServerPlayer, NavigationContext> activeByPlayer) {
        Set<VehicleEntity> activeVehicles = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (NavigationContext navigation : activeByPlayer.values()) {
            activeVehicles.add(navigation.vehicle());
        }
        long serverTick = server.getTickCount();
        for (VehicleEntity vehicle : activeVehicles) {
            ServerLevel level = (ServerLevel) vehicle.level();
            ServerChunkCache chunkSource = level.getChunkSource();
            DistanceManager distanceManager = ((ServerChunkCacheInvoker) chunkSource).iacruise$getDistanceManager();
            long chunkKey = vehicle.chunkPosition().toLong();
            ChunkHolder holder = findLoadingHolder(chunkSource.chunkMap, chunkKey);
            boolean alwaysTicking = vehicle.isAlwaysTicking();
            boolean entityListContains = ((ServerLevelAccessor) level).iacruise$getEntityTickList().contains(vehicle);
            boolean entityTickingRange = distanceManager.inEntityTickingRange(chunkKey);
            EntityChunkTicketState ticketState = ENTITY_CHUNK_TICKETS.get(vehicle);
            boolean ownsEntityTicket = ticketState != null && ticketState.chunkKeys().contains(chunkKey);
            String holderStatus = holder == null ? "missing" : holder.getFullStatus().toString();
            String signature = alwaysTicking + ":" + entityListContains + ":" + entityTickingRange + ":"
                    + ownsEntityTicket + ":" + holderStatus + ":" + vehicle.isRemoved();
            EntityTickDiagnostic previous = ENTITY_TICK_DIAGNOSTICS.get(vehicle);
            boolean progressed = previous == null || previous.vehicleTick() != vehicle.tickCount;
            int stalledTicks = progressed ? 0 : previous.stalledTicks() + 1;
            String state = entityTickState(alwaysTicking, entityListContains, entityTickingRange, progressed);
            boolean changed = previous == null || !signature.equals(previous.signature());
            boolean recovered = progressed && previous != null && previous.stalledTicks() > 0;
            boolean stalled = stalledTicks >= 20 && stalledTicks % 20 == 0;
            if (stalled || changed || recovered) {
                Object[] arguments = {serverTick, vehicle.getId(), vehicle.tickCount, stalledTicks,
                        vehicle.chunkPosition(), state, alwaysTicking, entityListContains, entityTickingRange,
                        holderStatus, holder == null ? -1 : holder.getTicketLevel(), ownsEntityTicket,
                        vehicle.isRemoved(), vehicle.getPassengers().size(), vehicle.getControllingPassenger()};
                if (stalled) {
                    ImmersiveAircraftCruise.LOGGER.warn(ENTITY_TICK_MESSAGE, arguments);
                } else {
                    CruiseDebug.info(ImmersiveAircraftCruise.LOGGER, ENTITY_TICK_MESSAGE, arguments);
                }
            }
            logBroadcastGap(vehicle);
            if (serverTick % CRUISE_STANDSTILL_CHECK_INTERVAL_TICKS == 0) {
                checkCruiseStandstill(vehicle, serverTick);
            }
            ENTITY_TICK_DIAGNOSTICS.put(vehicle,
                    new EntityTickDiagnostic(vehicle.tickCount, stalledTicks, signature));
        }
        ENTITY_TICK_DIAGNOSTICS.keySet().removeIf(vehicle ->
                vehicle.isRemoved() || !activeVehicles.contains(vehicle));
        LAST_BROADCAST.keySet().removeIf(vehicle ->
                vehicle.isRemoved() || !activeVehicles.contains(vehicle));
        STANDSTILL_MARKS.keySet().removeIf(vehicle ->
                vehicle.isRemoved() || !activeVehicles.contains(vehicle));
        STANDSTILL_REPORTS.keySet().removeIf(vehicle ->
                vehicle.isRemoved() || !activeVehicles.contains(vehicle));
        HAS_FLOWN.removeIf(vehicle ->
                vehicle.isRemoved() || !activeVehicles.contains(vehicle));
    }

    /**
     * Detects an aircraft that stopped making progress while it still has a leg to fly.
     *
     * <p>The window is simply "has not moved for three seconds": a frozen copy leaves the authoritative
     * position untouched, while any real movement - even a slow one that stays inside one chunk - closes
     * the window. Everything else is already guaranteed by the caller, because a vehicle only reaches
     * this loop through a navigation context: it is on a preload route, has a remaining target, is not in
     * holding pattern and has a rider. The last {@link #CRUISE_STANDSTILL_MIN_TARGET_DISTANCE} blocks
     * before the final target are the one exception, since approach, touchdown and hover stand still
     * there by design.
     */
    private static void checkCruiseStandstill(VehicleEntity vehicle, long serverTick) {
        StandstillMark previous = STANDSTILL_MARKS.get(vehicle);
        if (previous == null || previous.moved(vehicle)) {
            if (previous != null) {
                HAS_FLOWN.add(vehicle);
            }
            STANDSTILL_REPORTS.remove(vehicle);
            STANDSTILL_MARKS.put(vehicle, StandstillMark.of(vehicle, serverTick));
            return;
        }
        if (serverTick - previous.sinceTick() < CRUISE_STANDSTILL_TICKS) {
            return;
        }
        STANDSTILL_MARKS.put(vehicle, StandstillMark.of(vehicle, serverTick));
        if (!HAS_FLOWN.contains(vehicle) || isNearFinalTarget(vehicle)) {
            // Waiting on the ground before the first take-off, or inside the landing stretch: both are
            // normal places to stand still, so only an aircraft that has flown and then stops is stuck.
            return;
        }
        ImmersiveAircraftCruise.LOGGER.warn(
                "[CruiseRide] aircraft did not move for {} ticks while cruising: vehicleId={}, chunk={}, riders={}",
                CRUISE_STANDSTILL_TICKS, vehicle.getId(), vehicle.chunkPosition(),
                vehicle.getPassengers().size());
        reportHookOwners("cruise standstill");
        int reports = STANDSTILL_REPORTS.merge(vehicle, 1, Integer::sum);
        for (Entity passenger : List.copyOf(vehicle.getPassengers())) {
            if (passenger instanceof ServerPlayer rider) {
                // A standstill while cruising is proof that the aircraft is not moving, which is the one
                // piece of evidence a frozen client cannot produce for itself: a copy that never ticks
                // does not drift either, so neither rider's heartbeat check ever fires. Rebuilding is
                // therefore forced here, and the control seat only waits for a second verdict because a
                // single one can also come from a server that stalled under load.
                boolean controlSeat = vehicle.getControllingPassenger() == rider;
                // The server's own detector has no client report to compare against, so it uses the
                // two-argument reset, whose log line reports controlSeatSplit=n/a.
                if (!controlSeat || reports >= 2) {
                    hardResetRideState(rider, vehicle.getId());
                } else {
                    repairRideState(rider, vehicle.getId());
                }
            }
        }
    }

    /** True inside the last {@link #CRUISE_STANDSTILL_MIN_TARGET_DISTANCE} blocks before the target. */
    private static boolean isNearFinalTarget(VehicleEntity vehicle) {
        CruiseRoute route = CruiseController.currentRoute(vehicle);
        CruiseRoute.Waypoint finalTarget = route.getFinalTarget();
        if (finalTarget == null) {
            return true;
        }
        double dx = finalTarget.x() + 0.5d - vehicle.getX();
        double dz = finalTarget.z() + 0.5d - vehicle.getZ();
        return dx * dx + dz * dz
                <= CRUISE_STANDSTILL_MIN_TARGET_DISTANCE * CRUISE_STANDSTILL_MIN_TARGET_DISTANCE;
    }

    /**
     * Warns when an occupied aircraft moved without a single state broadcast, which is the server-side
     * result the client needs: it is independent of why the broadcast was missing (no observers paired,
     * a suppressed tracking pass, or anything else).
     */
    private static void logBroadcastGap(VehicleEntity vehicle) {
        if (vehicle.getPassengers().isEmpty()) {
            LAST_BROADCAST.remove(vehicle);
            return;
        }
        BroadcastMark mark = LAST_BROADCAST.get(vehicle);
        if (mark == null) {
            LAST_BROADCAST.put(vehicle, new BroadcastMark(vehicle.tickCount,
                    vehicle.getX(), vehicle.getY(), vehicle.getZ()));
            return;
        }
        int gapTicks = vehicle.tickCount - mark.vehicleTick();
        double moved = Math.abs(vehicle.getX() - mark.x())
                + Math.abs(vehicle.getY() - mark.y())
                + Math.abs(vehicle.getZ() - mark.z());
        if (gapTicks < BROADCAST_GAP_TICKS || moved < BROADCAST_GAP_MIN_BLOCKS) {
            return;
        }
        ImmersiveAircraftCruise.LOGGER.warn(
                "[CruiseRide] aircraft state not broadcast: vehicleId={}, riders={}, gapTicks={}, movedBlocks={}",
                vehicle.getId(), vehicle.getPassengers().size(), gapTicks, String.format("%.1f", moved));
        LAST_BROADCAST.put(vehicle, new BroadcastMark(vehicle.tickCount,
                vehicle.getX(), vehicle.getY(), vehicle.getZ()));
    }

    private static String entityTickState(boolean alwaysTicking, boolean entityListContains,
                                          boolean entityTickingRange, boolean progressed) {
        if (!alwaysTicking) {
            return "not-always-ticking";
        }
        if (!entityListContains) {
            return "not-in-entity-tick-list";
        }
        if (!entityTickingRange) {
            return "out-of-entity-ticking-range";
        }
        return progressed ? "ticking" : "eligible-but-tick-not-observed";
    }

    /**
     * Any module-equipped aircraft with a player aboard needs its own chunk kept loaded and ticking.
     *
     * <p>This deliberately does not depend on the navigation state. A collision stops navigation,
     * and an earlier version released the aircraft's ticket at exactly that moment: the chunk could
     * then unload, the vehicle entity was removed by the chunk system (`Entity#remove` ejects its
     * passengers), and the player was dismounted server-side while the client - whose chunk cache
     * had merely stopped ticking the entity - kept riding. When the chunk came back the entity was
     * re-created and the client re-attached, which is the "dismount on impact, remount after the
     * chunk loads again" cycle. Keeping the ticket while anyone is aboard removes the whole chain.
     */
    private static boolean requiresEntityChunkTicket(VehicleEntity vehicle) {
        if (vehicle.level().isClientSide()
                || !CruiseController.hasCruiseModule(vehicle)) {
            return false;
        }
        for (Entity passenger : vehicle.getPassengers()) {
            if (passenger instanceof ServerPlayer) {
                return true;
            }
        }
        return false;
    }

    private static void releaseEntityChunkTicket(VehicleEntity vehicle, EntityChunkTicketState state) {
        DistanceManager distanceManager = ((ServerChunkCacheInvoker) state.chunkSource())
                .iacruise$getDistanceManager();
        removeEntityChunkTicket(distanceManager, state);
        ENTITY_CHUNK_TICKETS.remove(vehicle);
        distanceManager.runAllUpdates(state.chunkSource().chunkMap);
    }

    private static void removeEntityChunkTicket(DistanceManager distanceManager, EntityChunkTicketState state) {
        for (long chunkKey : state.chunkKeys()) {
            removeEntityChunkTicket(distanceManager, new ChunkPos(chunkKey), state.vehicleId());
        }
    }

    private static void removeEntityChunkTicket(DistanceManager distanceManager, ChunkPos chunkPos, int vehicleId) {
        distanceManager.removeTicket(ROUTE_ENTITY_CHUNK_TICKET, chunkPos,
                ROUTE_ENTITY_CHUNK_TICKET_LEVEL, vehicleId);
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
            RIDE_RESYNC_COOLDOWNS.remove(player);
            HARD_RESET_COOLDOWNS.remove(player);
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

    private static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            CruiseController.handlePilotLoggedOut(player);
            stopPilotNavigation(player, "dimension-change");
            PENDING_TELEPORTS.remove(player);
            RIDE_RESYNC_COOLDOWNS.remove(player);
            HARD_RESET_COOLDOWNS.remove(player);
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
        // The preload screen owns the auto-slowdown switch, so every session starts from the safe default.
        CruiseController.setPreloadAutoDeceleration(true);
        NETWORK_STATES.clear();
        PENDING_TELEPORTS.clear();
        RIDE_RESYNC_COOLDOWNS.clear();
        HARD_RESET_COOLDOWNS.clear();
        CONTEXT_STATUSES.clear();
        ENTITY_CHUNK_TICKETS.clear();
        ENTITY_TICK_DIAGNOSTICS.clear();
        LAST_BROADCAST.clear();
        RIDE_MEMORY.clear();
        CACHE_INVALIDATIONS.clear();
        STANDSTILL_MARKS.clear();
        STANDSTILL_REPORTS.clear();
        anomalyReported = false;
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

    private record EntityChunkTicketState(ServerChunkCache chunkSource, LinkedHashSet<Long> chunkKeys, int vehicleId) {
    }

    private record EntityTickDiagnostic(int vehicleTick, int stalledTicks, String signature) {
    }

    /** Where and when the aircraft's own state last reached a client. */
    private record BroadcastMark(int vehicleTick, double x, double y, double z) {
    }

    /** A standstill window: when it was opened and where, so movement can close it. */
    private record StandstillMark(long sinceTick, double x, double y, double z) {
        static StandstillMark of(VehicleEntity vehicle, long tick) {
            return new StandstillMark(tick, vehicle.getX(), vehicle.getY(), vehicle.getZ());
        }

        boolean moved(VehicleEntity vehicle) {
            return Math.abs(vehicle.getX() - x) + Math.abs(vehicle.getY() - y)
                    + Math.abs(vehicle.getZ() - z) > CRUISE_STANDSTILL_MIN_MOVE_BLOCKS;
        }
    }

    private record RideMemory(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
                              Set<UUID> passengerIds, long tick) {
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
