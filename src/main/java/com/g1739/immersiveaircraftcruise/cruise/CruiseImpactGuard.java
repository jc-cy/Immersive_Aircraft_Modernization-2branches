package com.g1739.immersiveaircraftcruise.cruise;

import com.g1739.immersiveaircraftcruise.network.CruiseNetwork;
import com.g1739.immersiveaircraftcruise.network.PlayCruiseGuardEffectPacket;
import immersive_aircraft.entity.InventoryVehicleEntity;
import immersive_aircraft.entity.VehicleEntity;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;

/**
 * Aircraft impact protection ("损毁保护") carried by the cruise module.
 *
 * <p>Authority lives on the server: the module's NBT holds the cooldown deadline, and the three
 * second invulnerability window is runtime state on the aircraft. The client only displays it.
 *
 * <p>Only one decision point is used - Immersive Aircraft funnels every damaging death path through
 * {@code VehicleEntity#applyDamage(float, boolean)}, so intercepting there needs no damage-type
 * taxonomy. Paths that never apply damage ({@code /kill}, creative-mode removal, {@code discard()})
 * are intentionally not intercepted.
 */
public final class CruiseImpactGuard {
    /** Three seconds of complete damage immunity after a lethal hit has been negated. */
    public static final long INVULNERABLE_TICKS = 60L;
    /** 200 seconds of recharging before the protection can trigger again (0.5% per second). */
    public static final long COOLDOWN_TICKS = 4000L;

    /** Cached module presence per aircraft, used to push a fresh module stack when it changes. */
    private static final Map<Integer, Boolean> MODULE_PRESENCE = new HashMap<>();
    private static final int PRESENCE_POLL_INTERVAL_TICKS = 20;
    private static final int TICKS_PER_SECOND = 20;

    private CruiseImpactGuard() {
    }

    /**
     * Server side only. Called from the head of {@code VehicleEntity#applyDamage}.
     *
     * @return true when the damage must be cancelled
     */
    public static boolean guardDamage(VehicleEntity vehicle, float amount) {
        if (amount <= 0.0f) {
            return false;
        }
        ItemStack module = CruiseModuleData.findModule(vehicle).orElse(null);
        if (module == null) {
            return false;
        }
        CruiseVehicleAccess access = (CruiseVehicleAccess) vehicle;
        long now = vehicle.level().getGameTime();

        // Armed window: no durability/health loss at all for three seconds.
        if (now < access.iacruise$guardWindowEnd()) {
            return true;
        }

        // Not lethal: the normal damage path keeps working.
        if (amount < vehicle.getHealth()) {
            return false;
        }

        CruiseRoute route = CruiseModuleData.read(module);
        if (CruiseModuleData.isGuardCooling(module, now)) {
            // Cooling down: this hit kills the aircraft exactly like vanilla Immersive Aircraft.
            return false;
        }
        if (!route.isImpactGuardAlways() && !route.isEnabled()) {
            // "Navigation only" mode without a running auto navigation.
            return false;
        }

        trigger(vehicle, access, now);
        return true;
    }

    private static void trigger(VehicleEntity vehicle, CruiseVehicleAccess access, long now) {
        vehicle.setHealth(1.0f);
        access.iacruise$setGuardWindowEnd(now + INVULNERABLE_TICKS);
        CruiseModuleData.setGuardCooldownEnd(vehicle, now + COOLDOWN_TICKS);
        playFeedback(vehicle);
        // "Sync more, never less": refresh every rider's copy of the module stack right away.
        CruiseController.syncVehicleInventoryToPassengers(vehicle);
    }

    private static void playFeedback(VehicleEntity vehicle) {
        vehicle.level().playSound(null, vehicle.getX(), vehicle.getY(), vehicle.getZ(),
                SoundEvents.TOTEM_USE, SoundSource.PLAYERS, 1.0f, 1.0f);
        if (vehicle.level() instanceof ServerLevel serverLevel) {
            double width = Math.max(0.5, vehicle.getBbWidth() * 0.5);
            double height = Math.max(0.5, vehicle.getBbHeight() * 0.5);
            serverLevel.sendParticles(ParticleTypes.TOTEM_OF_UNDYING,
                    vehicle.getX(), vehicle.getY() + vehicle.getBbHeight() * 0.5, vehicle.getZ(),
                    40, width, height, width, 0.0);
        }
        for (Entity passenger : vehicle.getPassengers()) {
            if (passenger instanceof ServerPlayer serverPlayer) {
                CruiseNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> serverPlayer),
                        new PlayCruiseGuardEffectPacket(vehicle.getId()));
            }
        }
    }

    /**
     * Server side only. Detects the module being installed into or removed from an aircraft and
     * pushes a fresh inventory snapshot, so no client keeps a stale copy of the module data.
     */
    public static void pollModulePresence(VehicleEntity vehicle) {
        if (vehicle.level().isClientSide() || !(vehicle instanceof InventoryVehicleEntity)) {
            return;
        }
        if (vehicle.isRemoved()) {
            MODULE_PRESENCE.remove(vehicle.getId());
            return;
        }
        if (vehicle.tickCount % PRESENCE_POLL_INTERVAL_TICKS != 0 || vehicle.getPassengers().isEmpty()) {
            return;
        }
        boolean present = CruiseModuleData.hasModule(vehicle);
        Boolean previous = MODULE_PRESENCE.put(vehicle.getId(), present);
        if (previous != null && previous != present) {
            CruiseController.syncVehicleInventoryToPassengers(vehicle);
        }
    }

    /**
     * Remaining cooldown in whole seconds, rounded up. Both the tooltip readout and the icon overlay
     * are recomputed every frame, so quantising to seconds is what makes the readout step exactly
     * once per second (0.5% at a time) instead of five times per second.
     */
    public static long remainingGuardSeconds(long cooldownEnd, long now) {
        long remainingTicks = Math.max(0L, cooldownEnd - now);
        return (remainingTicks + TICKS_PER_SECOND - 1L) / TICKS_PER_SECOND;
    }

    /** Charge percentage (0.0 at the trigger moment, 100.0 when ready) at second granularity. */
    public static float guardChargePercent(long cooldownEnd, long now) {
        double totalSeconds = COOLDOWN_TICKS / (double) TICKS_PER_SECOND;
        long remainingSeconds = remainingGuardSeconds(cooldownEnd, now);
        double charge = (totalSeconds - remainingSeconds) * 100.0 / totalSeconds;
        return (float) Math.max(0.0, Math.min(100.0, charge));
    }
}
