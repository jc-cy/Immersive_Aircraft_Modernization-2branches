package com.g1739.immersiveaircraftcruise.network;

import com.g1739.immersiveaircraftcruise.client.ClientPacketHandlers;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record CruiseRouteChunkPacket(String namespace, String dimension,
                                     int x, int z, long hash, byte[] compressedPayload) {
    private static final int MAX_COMPRESSED_PAYLOAD = 4 * 1024 * 1024;

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeUtf(namespace, 64);
        buffer.writeUtf(dimension, 128);
        buffer.writeInt(x);
        buffer.writeInt(z);
        buffer.writeLong(hash);
        buffer.writeByteArray(compressedPayload);
    }

    public static CruiseRouteChunkPacket decode(FriendlyByteBuf buffer) {
        return new CruiseRouteChunkPacket(
                buffer.readUtf(64), buffer.readUtf(128), buffer.readInt(), buffer.readInt(),
                buffer.readLong(), buffer.readByteArray(MAX_COMPRESSED_PAYLOAD));
    }

    public static void handle(CruiseRouteChunkPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientPacketHandlers.enqueueCruiseChunk(packet));
        context.setPacketHandled(true);
    }
}
