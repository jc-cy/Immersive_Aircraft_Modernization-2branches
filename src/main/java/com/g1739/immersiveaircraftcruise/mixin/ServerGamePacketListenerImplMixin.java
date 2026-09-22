package com.g1739.immersiveaircraftcruise.mixin;

import com.g1739.immersiveaircraftcruise.ImmersiveAircraftCruise;
import com.g1739.immersiveaircraftcruise.CruiseDebug;
import com.g1739.immersiveaircraftcruise.cruise.CruiseController;
import immersive_aircraft.entity.VehicleEntity;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adapts vanilla's vehicle movement guard to cruise speeds while keeping every vanilla protection.
 *
 * <p>Vanilla's {@code handleMoveVehicle} rejects a move when
 * {@code |reported - vehicleFirstGood|^2 - |deltaMovement|^2 > 100.0}. {@code vehicleFirstGood} is
 * sampled once per connection tick, so the measurement is "distance travelled since the last server
 * tick". That is fine for a boat or a vanilla aircraft, but not for this mod:
 *
 * <ul>
 *   <li>a cruise aircraft flies up to roughly six blocks per tick, so two move packets handled in
 *       the same server tick already exceed the ten block budget;</li>
 *   <li>a server stall while preloading route chunks is far worse. Host logs from 2026-09-20 show
 *       {@code Can't keep up! Running 2347ms or 46 ticks behind} followed by 166
 *       {@code moved too quickly} rejections with distances of 100-270 blocks, because the
 *       aircraft's server position never advanced while the pilot's client kept flying.</li>
 * </ul>
 *
 * <p>Vanilla answers a rejection by teleporting the vehicle back to its older server position and
 * sending {@code ClientboundMoveVehiclePacket}; the controlling client applies that with
 * {@code absMoveTo}, which is the "aircraft is yanked backwards" stutter seen by the pilot, and it
 * leaves the server-side aircraft (and every passenger's chunk tracking center) behind the pilot.
 * <p>The fix never touches that constant, because other mods already modify it (for example
 * {@code toofast} in the DeceasedCraft pack sets it to {@code Double.MAX_VALUE}) and a second
 * {@code @ModifyConstant} on the same constant is a hard Mixin conflict: one modifier is skipped and
 * the skipped modifier aborts startup when it requires a successful injection. An {@code @Inject}
 * never conflicts, so the relaxation is done purely by feeding the check a value it must accept:
 *
 * <ul>
 *   <li>{@code vehicleFirstGood*} is private state read only by this speed check - the swept move
 *       below re-derives its delta from {@code vehicleLastGood*} - so pointing it at the reported
 *       position makes the check measure a zero step;</li>
 *   <li>that happens for every cruise move packet, unconditionally.</li>
 * </ul>
 *
 * <p>This is the same outcome the {@code toofast} mod produces by replacing the constant with
 * {@code Double.MAX_VALUE}, but expressed without touching a constant another mod may also modify,
 * and without any self-invented ceiling or rate limit: the spec is simply "a cruise aircraft is not
 * speed-checked". Invalid values still disconnect and coordinates stay clamped.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
    @Shadow
    public ServerPlayer player;

    @Shadow
    private Entity lastVehicle;

    @Shadow
    private double vehicleFirstGoodX;

    @Shadow
    private double vehicleFirstGoodY;

    @Shadow
    private double vehicleFirstGoodZ;

    /**
     * Keeps the speed check's baseline current for cruise aircraft without modifying its constant.
     *
     * <p>The injector runs before {@code PacketUtils.ensureRunningOnSameThread}, which re-queues
     * packets that arrive on a Netty thread. Returning early in that case keeps every write on the
     * server thread; the queued handling re-enters this method on the correct thread.
     *
     * <p>The server instance is read from {@code ServerPlayer#server} instead of being shadowed:
     * {@code ServerGamePacketListenerImpl} inherits that field from
     * {@code ServerCommonPacketListenerImpl}, and {@code @Shadow} only resolves members declared by
     * the target class itself.
     */
    @Inject(method = "handleMoveVehicle", at = @At("HEAD"))
    private void iacruise$refreshCruiseMoveBaseline(ServerboundMoveVehiclePacket packet, CallbackInfo ci) {
        if (!player.server.isSameThread()) {
            return;
        }
        VehicleEntity vehicle = iacruise$cruisePilotVehicle();
        if (vehicle == null) {
            return;
        }
        // The field is read only by the speed check (the swept move below re-derives its delta from
        // vehicleLastGood), so pointing it at the reported position makes that check measure a zero
        // step: a cruise aircraft is simply never speed-checked.
        vehicleFirstGoodX = packet.getX();
        vehicleFirstGoodY = packet.getY();
        vehicleFirstGoodZ = packet.getZ();
    }

    /** Keeps the server-side pilot and its chunk tracking center attached to the cruise vehicle. */
    @Inject(method = "handleMoveVehicle", at = @At("RETURN"))
    private void iacruise$positionCruiseVehiclePilot(ServerboundMoveVehiclePacket packet, CallbackInfo ci) {
        VehicleEntity vehicle = iacruise$cruisePilotVehicle();
        if (vehicle == null) {
            return;
        }

        double distanceBefore = player.distanceTo(vehicle);
        CruiseController.synchronizeCruiseMovement(vehicle, "move-packet");
        if (distanceBefore > 16.0d) {
            CruiseDebug.info(ImmersiveAircraftCruise.LOGGER,
                    "[CruiseChunks] corrected server pilot position: player={}, vehicleId={}, distanceBefore={}",
                    player.getScoreboardName(), vehicle.getId(), distanceBefore);
        }
    }

    /** Returns the root vehicle when this connection is the pilot of a module-equipped aircraft. */
    private VehicleEntity iacruise$cruisePilotVehicle() {
        Entity root = player.getRootVehicle();
        return root instanceof VehicleEntity vehicle
                && root == lastVehicle
                && CruiseController.isPilot(vehicle, player)
                && CruiseController.hasCruiseModule(vehicle)
                ? vehicle
                : null;
    }
}
