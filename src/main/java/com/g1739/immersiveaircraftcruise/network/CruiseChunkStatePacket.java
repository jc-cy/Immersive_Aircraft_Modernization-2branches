package com.g1739.immersiveaircraftcruise.network;

import com.g1739.immersiveaircraftcruise.cruise.CruiseChunkSendScheduler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record CruiseChunkStatePacket(int x, int z, long hash, State state) {
    public enum State {
        ACTIVE,
        EVICTED,
        MISSING
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeInt(x);
        buffer.writeInt(z);
        buffer.writeLong(hash);
        buffer.writeEnum(state);
    }

    public static CruiseChunkStatePacket decode(FriendlyByteBuf buffer) {
        return new CruiseChunkStatePacket(buffer.readInt(), buffer.readInt(), buffer.readLong(), buffer.readEnum(State.class));
    }

    public static void handle(CruiseChunkStatePacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            CruiseChunkSendScheduler.updateClientChunkState(player, packet.x(), packet.z(), packet.hash(), packet.state());
        });
        context.setPacketHandled(true);
    }
}
