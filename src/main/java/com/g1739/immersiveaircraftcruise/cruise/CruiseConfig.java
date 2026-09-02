package com.g1739.immersiveaircraftcruise.cruise;

import net.minecraftforge.common.ForgeConfigSpec;

/** Server/client-shared gameplay tuning for cruise navigation. */
public final class CruiseConfig {
    public static final ForgeConfigSpec.DoubleValue SUPER_ACCELERATION_POWER_BONUS;
    public static final ForgeConfigSpec.DoubleValue SUPER_ACCELERATION_FUEL_BONUS;
    public static final ForgeConfigSpec.DoubleValue NORMAL_POWER_BONUS;
    public static final ForgeConfigSpec.DoubleValue NORMAL_FUEL_BONUS;
    public static final ForgeConfigSpec.DoubleValue ECO_POWER_BONUS;
    public static final ForgeConfigSpec.DoubleValue ECO_FUEL_BONUS;
    public static final ForgeConfigSpec.DoubleValue ROTORCRAFT_SPEED_LIMIT;
    public static final ForgeConfigSpec SPEC;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.push("cruise_modes");
        SUPER_ACCELERATION_POWER_BONUS = builder
                .comment("Super acceleration mode power bonus. 1.0 means +100%, 0 means no change.")
                .defineInRange("super_acceleration_power_bonus", 1.0d, -1.0d, 10.0d);
        SUPER_ACCELERATION_FUEL_BONUS = builder
                .comment("Super acceleration mode fuel consumption bonus. 3.0 means +300%, -0.75 means -75%.")
                .defineInRange("super_acceleration_fuel_bonus", 3.0d, -1.0d, 20.0d);
        NORMAL_POWER_BONUS = builder
                .comment("Normal mode power bonus. 0.2 means +20%.")
                .defineInRange("normal_power_bonus", 0.2d, -1.0d, 10.0d);
        NORMAL_FUEL_BONUS = builder
                .comment("Normal mode fuel consumption bonus. 0 means unchanged.")
                .defineInRange("normal_fuel_bonus", 0.0d, -1.0d, 20.0d);
        ECO_POWER_BONUS = builder
                .comment("Eco mode power bonus. -0.15 means -15%.")
                .defineInRange("eco_power_bonus", -0.15d, -1.0d, 10.0d);
        ECO_FUEL_BONUS = builder
                .comment("Eco mode fuel consumption bonus. -0.75 means -75%.")
                .defineInRange("eco_fuel_bonus", -0.75d, -1.0d, 20.0d);
        builder.pop();

        builder.push("rotorcraft");
        ROTORCRAFT_SPEED_LIMIT = builder
                .comment("Optional horizontal speed limit for Rotorcraft (airships, airboats, etc.) during cruise navigation, in blocks per second. 0 disables the limit.")
                .defineInRange("speed_limit", 0.0d, 0.0d, 10000.0d);
        builder.pop();
        SPEC = builder.build();
    }

    private CruiseConfig() {
    }

    public static double powerBonus(CruiseRoute.CruiseMode mode, double fallback) {
        return switch (mode) {
            case SUPER_ACCELERATION -> value(SUPER_ACCELERATION_POWER_BONUS, fallback);
            case NORMAL -> value(NORMAL_POWER_BONUS, fallback);
            case ECO -> value(ECO_POWER_BONUS, fallback);
        };
    }

    public static double fuelBonus(CruiseRoute.CruiseMode mode, double fallback) {
        return switch (mode) {
            case SUPER_ACCELERATION -> value(SUPER_ACCELERATION_FUEL_BONUS, fallback);
            case NORMAL -> value(NORMAL_FUEL_BONUS, fallback);
            case ECO -> value(ECO_FUEL_BONUS, fallback);
        };
    }

    public static double rotorcraftSpeedLimit() {
        return value(ROTORCRAFT_SPEED_LIMIT, 0.0d);
    }

    private static double value(ForgeConfigSpec.DoubleValue value, double fallback) {
        try {
            double configured = value.get();
            return Double.isFinite(configured) ? configured : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }
}
