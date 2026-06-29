package com.g1739.immersiveaircraftcruise.cruise;

import net.minecraft.world.item.ItemStack;

public record CruiseFuelInfo(String amountText, int remainingTicks, ItemStack icon, float speed, boolean boosting) {
    public static final CruiseFuelInfo EMPTY = new CruiseFuelInfo("0", -1, ItemStack.EMPTY, 0.0f, false);

    public CruiseFuelInfo {
        if (amountText == null || amountText.isBlank()) {
            amountText = "0";
        }
        if (icon == null) {
            icon = ItemStack.EMPTY;
        }
        if (!Float.isFinite(speed) || speed < 0.0f) {
            speed = 0.0f;
        }
    }

    public CruiseFuelInfo(String amountText, int remainingTicks, ItemStack icon, float speed) {
        this(amountText, remainingTicks, icon, speed, false);
    }

    public CruiseFuelInfo(String amountText, int remainingTicks, ItemStack icon) {
        this(amountText, remainingTicks, icon, 0.0f, false);
    }

    public CruiseFuelInfo(int amount, int remainingTicks, ItemStack icon) {
        this(Integer.toString(amount), remainingTicks, icon, 0.0f, false);
    }

    public CruiseFuelInfo(int amount, int remainingTicks) {
        this(Integer.toString(amount), remainingTicks, ItemStack.EMPTY);
    }
}
