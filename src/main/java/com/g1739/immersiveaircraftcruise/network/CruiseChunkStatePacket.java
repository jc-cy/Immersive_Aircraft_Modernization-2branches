package com.g1739.immersiveaircraftcruise.network;


import com.g1739.immersiveaircraftcruise.cruise.CruiseChunkSendScheduler;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record CruiseChunkStatePacket(int x, int z, long hash, State state) implements CustomPacketPayload {
    public static final Type<CruiseChunkStatePacket> TYPE = CruiseNetwork.type("cruise_chunk_state");
    public static final StreamCodec<RegistryFriendlyByteBuf, CruiseChunkStatePacket> STREAM_CODEC = StreamCodec.ofMember(CruiseChunkStatePacket::encode, CruiseChunkStatePacket::decode);

    public enum State {
        ACTIVE,
        EVICTED,
        MISSING
    }

    public void encode(RegistryFriendlyByteBuf buffer) {
        buffer.writeInt(x);
        buffer.writeInt(z);
        buffer.writeLong(hash);
        buffer.writeEnum(state);
    }

    public static CruiseChunkStatePacket decode(RegistryFriendlyByteBuf buffer) {
        return new CruiseChunkStatePacket(buffer.readInt(), buffer.readInt(), buffer.readLong(), buffer.readEnum(State.class));
    }

    @Override
    public Type<CruiseChunkStatePacket> type() {
        return TYPE;
    }

    public static void handle(CruiseChunkStatePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            CruiseChunkSendScheduler.updateClientChunkState(player, packet.x(), packet.z(), packet.hash(), packet.state());
        });
    }
}
