package com.g1739.immersiveaircraftcruise.cruise;

import net.minecraft.world.item.ItemStack;

public record CruiseFuelInfo(String amountText, int remainingTicks, ItemStack icon) {
    public static final CruiseFuelInfo EMPTY = new CruiseFuelInfo("0", -1, ItemStack.EMPTY);

    public CruiseFuelInfo {
        if (amountText == null || amountText.isBlank()) {
            amountText = "0";
        }
        if (icon == null) {
            icon = ItemStack.EMPTY;
        }
    }

    public CruiseFuelInfo(int amount, int remainingTicks, ItemStack icon) {
        this(Integer.toString(amount), remainingTicks, icon);
    }

    public CruiseFuelInfo(int amount, int remainingTicks) {
        this(Integer.toString(amount), remainingTicks, ItemStack.EMPTY);
    }
}
