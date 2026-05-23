package com.g1739.immersiveaircraftcruise.network;

import net.minecraft.network.FriendlyByteBuf;

public enum RouteStorageTarget {
    VEHICLE_MODULE,
    HELD_MODULE;

    public void write(FriendlyByteBuf buffer) {
        buffer.writeEnum(this);
    }

    public static RouteStorageTarget read(FriendlyByteBuf buffer) {
        return buffer.readEnum(RouteStorageTarget.class);
    }
}
