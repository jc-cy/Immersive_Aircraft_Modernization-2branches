package com.g1739.immersiveaircraftcruise.cruise;

import net.minecraft.world.item.ItemStack;

public record CruiseFuelInfo(int amount, int remainingTicks, ItemStack icon) {
    public static final CruiseFuelInfo EMPTY = new CruiseFuelInfo(0, -1, ItemStack.EMPTY);

    public CruiseFuelInfo(int amount, int remainingTicks) {
        this(amount, remainingTicks, ItemStack.EMPTY);
    }
}
