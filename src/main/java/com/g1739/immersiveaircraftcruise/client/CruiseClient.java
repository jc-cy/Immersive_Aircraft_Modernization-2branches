package com.g1739.immersiveaircraftcruise.client;

import com.g1739.immersiveaircraftcruise.ImmersiveAircraftCruise;
import com.g1739.immersiveaircraftcruise.CruiseDebug;
import com.g1739.immersiveaircraftcruise.cruise.CruiseController;
import com.g1739.immersiveaircraftcruise.cruise.CruiseRoute;
import com.g1739.immersiveaircraftcruise.cruise.CruiseVehicleAccess;
import com.g1739.immersiveaircraftcruise.mixin.ClientChunkCacheInvoker;
import com.g1739.immersiveaircraftcruise.network.CruiseNetwork;
import com.g1739.immersiveaircraftcruise.network.RequestOpenCruiseScreenPacket;
import com.g1739.immersiveaircraftcruise.network.StopCruiseNavigationPacket;
import com.g1739.immersiveaircraftcruise.network.ToggleCruiseNavigationPacket;
import com.mojang.blaze3d.platform.InputConstants;
import immersive_aircraft.client.KeyBindings;
import immersive_aircraft.entity.VehicleEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

public final class CruiseClient {
    private static final int[] ROUTE_WINDOW_SAMPLES = {0, 1, 2, 3, 5, 10, 25, 50};

    public static final KeyMapping OPEN_CRUISE = new KeyMapping(
            "key.immersive_aircraft_cruise.open_cruise",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_COMMA,
            "key.categories.immersive_aircraft_cruise"
    );
    public static final KeyMapping TOGGLE_CRUISE = new KeyMapping(
            "key.immersive_aircraft_cruise.toggle_cruise",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_PERIOD,
            "key.categories.immersive_aircraft_cruise"
    );

    private CruiseClient() {
    }

    @Mod.EventBusSubscriber(modid = ImmersiveAircraftCruise.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class ModEvents {
        @SubscribeEvent
        public static void registerKeys(RegisterKeyMappingsEvent event) {
            event.register(OPEN_CRUISE);
            event.register(TOGGLE_CRUISE);
        }

        @SubscribeEvent
        public static void clientSetup(FMLClientSetupEvent event) {
            event.enqueueWork(CruiseItemProperties::register);
        }

        @SubscribeEvent
        public static void registerOverlays(RegisterGuiOverlaysEvent event) {
            event.registerAbove(VanillaGuiOverlay.HOTBAR.id(), "cruise_hud", CruiseHud::render);
        }
    }

    @Mod.EventBusSubscriber(modid = ImmersiveAircraftCruise.MOD_ID, value = Dist.CLIENT)
    public static final class ForgeEvents {
        private static boolean brakeWasDown;
        private static boolean dismountWasDown;
        private static long lastFlightChunkKey = Long.MIN_VALUE;
        private static boolean lastClientChunkLoaded;
        private static boolean lastHorizontalCollision;
        private static long lastFlightDiagnosticTick = Long.MIN_VALUE;
        private static double lastFlightX;
        private static double lastFlightY;
        private static double lastFlightZ;
        private static int stalledFlightTicks;
        private static String lastClientContextStatus;
        private static long lastClientContextDiagnosticTick = Long.MIN_VALUE;

        @SubscribeEvent
        public static void clientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            while (OPEN_CRUISE.consumeClick()) {
                CruiseNetwork.CHANNEL.sendToServer(openScreenPacket());
            }
            while (TOGGLE_CRUISE.consumeClick()) {
                CruiseNetwork.CHANNEL.sendToServer(togglePacket());
            }
            boolean brakeDown = KeyBindings.down.isDown();
            boolean dismountDown = KeyBindings.dismount.isDown();
            if ((brakeDown && !brakeWasDown) || (dismountDown && !dismountWasDown)) {
                sendStopPacket();
            }
            brakeWasDown = brakeDown;
            dismountWasDown = dismountDown;
            CruiseHud.clientTick();
            logClientFlightState();
            ClientPacketHandlers.processQueuedCruiseChunks();
        }

        @SubscribeEvent
        public static void clientLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
            ClientPacketHandlers.clearRouteSession();
        }

        @SubscribeEvent
        public static void clientLevelUnload(LevelEvent.Unload event) {
            if (event.getLevel() instanceof ClientLevel) {
                ClientPacketHandlers.clearRouteSession();
            }
        }

        private static void logClientFlightState() {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player == null || minecraft.level == null) {
                resetFlightDiagnostics();
                return;
            }
            ClientChunkCache chunkSource = minecraft.level.getChunkSource();
            ChunkPos playerChunk = minecraft.player.chunkPosition();
            maintainRouteCacheView(chunkSource, playerChunk);
            if (!CruiseDebug.enabled()) {
                return;
            }
            Entity root = minecraft.player.getRootVehicle();
            VehicleEntity vehicle = root instanceof VehicleEntity candidate ? candidate : null;
            CruiseVehicleAccess access = vehicle instanceof CruiseVehicleAccess candidate ? candidate : null;
            CruiseRoute route = access == null ? null : access.iacruise$getRoute();
            String inactiveStatus = null;
            if (vehicle == null) {
                inactiveStatus = "root=" + (root == null ? "null" : root.getClass().getSimpleName());
            } else if (access == null) {
                inactiveStatus = "vehicle=" + vehicle.getClass().getSimpleName() + ", noCruiseAccess";
            } else if (route == null) {
                inactiveStatus = "vehicle=" + vehicle.getClass().getSimpleName() + ", route=null";
            } else if (!route.isEnabled()) {
                inactiveStatus = "vehicle=" + vehicle.getClass().getSimpleName()
                        + ", enabled=false, holding=" + route.isHoldingPattern()
                        + ", hasTarget=" + route.hasTarget()
                        + ", routeIndex=" + route.getCurrentIndex();
            }
            if (inactiveStatus != null) {
                logInactiveClientContext(minecraft, root, inactiveStatus);
                resetFlightDiagnostics();
                return;
            }
            if (lastClientContextStatus != null) {
                CruiseDebug.info(ImmersiveAircraftCruise.LOGGER,
                        "[CruiseChunks][Client] flight context recovered: status={}, playerChunk={}, vehicleChunk={}",
                        lastClientContextStatus, playerChunk, new ChunkPos(vehicle.blockPosition()));
                lastClientContextStatus = null;
                lastClientContextDiagnosticTick = Long.MIN_VALUE;
            }

            ChunkPos vehicleChunk = new ChunkPos(vehicle.blockPosition());
            LevelChunk chunk = chunkSource.getChunk(vehicleChunk.x, vehicleChunk.z,
                    ChunkStatus.FULL, false);
            boolean loaded = chunk != null && !(chunk instanceof EmptyLevelChunk);
            boolean moved = vehicle.getX() != lastFlightX
                    || vehicle.getY() != lastFlightY
                    || vehicle.getZ() != lastFlightZ;
            if (moved) {
                stalledFlightTicks = 0;
            } else {
                stalledFlightTicks++;
            }
            long clientTick = minecraft.level.getGameTime();
            boolean changed = vehicleChunk.toLong() != lastFlightChunkKey
                    || loaded != lastClientChunkLoaded
                    || vehicle.horizontalCollision != lastHorizontalCollision;
            boolean periodic = lastFlightDiagnosticTick == Long.MIN_VALUE
                    || clientTick - lastFlightDiagnosticTick >= 20L;
            if (changed || periodic || !loaded || vehicle.horizontalCollision || stalledFlightTicks == 10) {
                String routeWindow = describeRouteWindow(vehicle, access.iacruise$getRoute(), chunkSource, vehicleChunk);
                String message = String.format(
                        "[CruiseChunks][Client] flight-check chunk=%s, loaded=%s, "
                                + "chunkType=%s, loadedChunks=%d, pos=%.2f/%.2f/%.2f, "
                                + "velocity=%.3f/%.3f/%.3f, horizontalCollision=%s, "
                                + "verticalCollision=%s, onGround=%s, routeIndex=%d, "
                                + "stalledTicks=%d, routeWindow=%s, playerChunk=%s, "
                                + "cacheCenter=%d/%d, cacheRadius=%d",
                        vehicleChunk, loaded,
                        chunk == null ? "null" : chunk.getClass().getSimpleName(),
                        chunkSource.getLoadedChunksCount(),
                        vehicle.getX(), vehicle.getY(), vehicle.getZ(),
                        vehicle.getDeltaMovement().x, vehicle.getDeltaMovement().y,
                        vehicle.getDeltaMovement().z, vehicle.horizontalCollision,
                        vehicle.verticalCollision, vehicle.onGround(),
                        access.iacruise$getRoute().getCurrentIndex(), stalledFlightTicks,
                        routeWindow, playerChunk,
                        CruiseClientCacheView.centerX(), CruiseClientCacheView.centerZ(),
                        CruiseClientCacheView.radius());
                if (!loaded || vehicle.horizontalCollision || stalledFlightTicks >= 10) {
                    ImmersiveAircraftCruise.LOGGER.warn(message);
                } else {
                    ImmersiveAircraftCruise.LOGGER.info(message);
                }
                lastFlightDiagnosticTick = clientTick;
            }
            lastFlightChunkKey = vehicleChunk.toLong();
            lastClientChunkLoaded = loaded;
            lastHorizontalCollision = vehicle.horizontalCollision;
            lastFlightX = vehicle.getX();
            lastFlightY = vehicle.getY();
            lastFlightZ = vehicle.getZ();
        }

        private static void logInactiveClientContext(Minecraft minecraft, Entity root, String status) {
            long gameTime = minecraft.level == null ? -1L : minecraft.level.getGameTime();
            if (!status.equals(lastClientContextStatus)
                    || lastClientContextDiagnosticTick == Long.MIN_VALUE
                    || gameTime - lastClientContextDiagnosticTick >= 20L) {
                ImmersiveAircraftCruise.LOGGER.warn(
                        "[CruiseChunks][Client] flight context inactive: status={}, playerChunk={}, "
                                + "playerPos={}, rootId={}, rootChunk={}, gameTime={}, thread={}",
                        status,
                        minecraft.player == null ? "none" : minecraft.player.chunkPosition(),
                        minecraft.player == null ? "none" : String.format("%.2f/%.2f/%.2f",
                                minecraft.player.getX(), minecraft.player.getY(), minecraft.player.getZ()),
                        root == null ? -1 : root.getId(),
                        root == null ? "none" : new ChunkPos(root.blockPosition()),
                        gameTime,
                        Thread.currentThread().getName());
                lastClientContextStatus = status;
                lastClientContextDiagnosticTick = gameTime;
            }
        }

        private static void maintainRouteCacheView(ClientChunkCache chunkSource, ChunkPos playerChunk) {
            if (CruiseClientCacheView.centerX() == playerChunk.x
                    && CruiseClientCacheView.centerZ() == playerChunk.z) {
                return;
            }
            ClientChunkCacheInvoker cache = (ClientChunkCacheInvoker) chunkSource;
            cache.iacruise$updateViewCenter(playerChunk.x, playerChunk.z);
        }

        private static String describeRouteWindow(VehicleEntity vehicle, CruiseRoute route,
                                                   ClientChunkCache chunkSource, ChunkPos vehicleChunk) {
            CruiseRoute.Waypoint target = route.getTarget();
            if (target == null) {
                return "none";
            }
            int directionX = Double.compare(target.x() + 0.5d, vehicle.getX());
            int directionZ = Double.compare(target.z() + 0.5d, vehicle.getZ());
            int sideX = -directionZ;
            int sideZ = directionX;
            StringBuilder result = new StringBuilder();
            for (int sampleIndex = 0; sampleIndex < ROUTE_WINDOW_SAMPLES.length; sampleIndex++) {
                int distance = ROUTE_WINDOW_SAMPLES[sampleIndex];
                if (sampleIndex > 0) {
                    result.append(';');
                }
                int centerX = vehicleChunk.x + directionX * distance;
                int centerZ = vehicleChunk.z + directionZ * distance;
                result.append(distance).append('[')
                        .append(clientChunkState(chunkSource, centerX, centerZ)).append('|')
                        .append(clientChunkState(chunkSource, centerX + sideX, centerZ + sideZ)).append('|')
                        .append(clientChunkState(chunkSource, centerX - sideX, centerZ - sideZ)).append(']');
            }
            return result.toString();
        }

        private static String clientChunkState(ClientChunkCache chunkSource, int x, int z) {
            LevelChunk chunk = chunkSource.getChunk(x, z, ChunkStatus.FULL, false);
            return chunk == null || chunk instanceof EmptyLevelChunk ? "missing" : "FULL";
        }

        private static void resetFlightDiagnostics() {
            lastFlightChunkKey = Long.MIN_VALUE;
            lastClientChunkLoaded = false;
            lastHorizontalCollision = false;
            lastFlightDiagnosticTick = Long.MIN_VALUE;
            lastFlightX = 0.0d;
            lastFlightY = 0.0d;
            lastFlightZ = 0.0d;
            stalledFlightTicks = 0;
        }

        private static ToggleCruiseNavigationPacket togglePacket() {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player == null) {
                return new ToggleCruiseNavigationPacket();
            }
            Entity root = minecraft.player.getRootVehicle();
            if (root instanceof VehicleEntity vehicle && vehicle instanceof CruiseVehicleAccess access) {
                return new ToggleCruiseNavigationPacket(vehicle.getId(), access.iacruise$getRoute());
            }
            return new ToggleCruiseNavigationPacket();
        }

        private static RequestOpenCruiseScreenPacket openScreenPacket() {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player == null) {
                return new RequestOpenCruiseScreenPacket();
            }
            Entity root = minecraft.player.getRootVehicle();
            if (root instanceof VehicleEntity vehicle && vehicle instanceof CruiseVehicleAccess access) {
                return new RequestOpenCruiseScreenPacket(vehicle.getId(), access.iacruise$getRoute());
            }
            return new RequestOpenCruiseScreenPacket();
        }

        private static void sendStopPacket() {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player == null) {
                return;
            }
            Entity root = minecraft.player.getRootVehicle();
            if (root instanceof VehicleEntity vehicle && vehicle instanceof CruiseVehicleAccess access) {
                if (vehicle.getControllingPassenger() == minecraft.player) {
                    CruiseRoute localRoute = access.iacruise$getRoute();
                    CruiseNetwork.CHANNEL.sendToServer(new StopCruiseNavigationPacket(vehicle.getId(), localRoute));
                    if (localRoute != null && localRoute.isEnabled()) {
                        stopLocalNavigation(vehicle, access, localRoute);
                    }
                }
            }
        }

        private static void stopLocalNavigation(VehicleEntity vehicle, CruiseVehicleAccess access, CruiseRoute route) {
            CruiseRoute stopped = route == null ? CruiseRoute.empty() : route.copy();
            stopped.stopNavigation();
            access.iacruise$setRoute(stopped);
            CruiseController.stopNavigationEffects(vehicle);
            CruiseController.clearCruiseInputs(vehicle);
        }
    }
}
