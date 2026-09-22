package com.g1739.immersiveaircraftcruise.network;


import com.g1739.immersiveaircraftcruise.client.ClientPacketHandlers;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record CruiseRouteChunkPacket(String namespace, String dimension,
                                     int x, int z, long hash, byte[] compressedPayload) implements CustomPacketPayload {
    public static final Type<CruiseRouteChunkPacket> TYPE = CruiseNetwork.type("cruise_route_chunk");
    public static final StreamCodec<RegistryFriendlyByteBuf, CruiseRouteChunkPacket> STREAM_CODEC = StreamCodec.ofMember(CruiseRouteChunkPacket::encode, CruiseRouteChunkPacket::decode);

    private static final int MAX_COMPRESSED_PAYLOAD = 4 * 1024 * 1024;

    public void encode(RegistryFriendlyByteBuf buffer) {
        buffer.writeUtf(namespace, 64);
        buffer.writeUtf(dimension, 128);
        buffer.writeInt(x);
        buffer.writeInt(z);
        buffer.writeLong(hash);
        buffer.writeByteArray(compressedPayload);
    }

    public static CruiseRouteChunkPacket decode(RegistryFriendlyByteBuf buffer) {
        return new CruiseRouteChunkPacket(
                buffer.readUtf(64), buffer.readUtf(128), buffer.readInt(), buffer.readInt(),
                buffer.readLong(), buffer.readByteArray(MAX_COMPRESSED_PAYLOAD));
    }

    @Override
    public Type<CruiseRouteChunkPacket> type() {
        return TYPE;
    }

    public static void handle(CruiseRouteChunkPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> ClientPacketHandlers.enqueueCruiseChunk(packet));
    }
}
