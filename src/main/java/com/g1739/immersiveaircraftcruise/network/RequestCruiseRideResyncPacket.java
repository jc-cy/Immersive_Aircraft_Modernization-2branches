package com.g1739.immersiveaircraftcruise.network;

import com.g1739.immersiveaircraftcruise.cruise.CruiseChunkSendScheduler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Sent when the client's own riding state disagrees with the server's 5-tick onboard heartbeat
 * ({@code UpdateCruiseFuelPacket} already carries the aircraft id it is sent for).
 *
 * <p>Two mismatches are possible: the server counts the player onboard while the client lost the
 * aircraft or its attachment, or the client still rides an aircraft the server no longer counts it
 * on. The server decides what to do, so the client never guesses.
 */
public class RequestCruiseRideResyncPacket {
    private final int vehicleId;

    public RequestCruiseRideResyncPacket(int vehicleId) {
        this.vehicleId = vehicleId;
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeInt(vehicleId);
    }

    public static RequestCruiseRideResyncPacket decode(FriendlyByteBuf buffer) {
        return new RequestCruiseRideResyncPacket(buffer.readInt());
    }

    public static void handle(RequestCruiseRideResyncPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                CruiseChunkSendScheduler.repairRideState(player, packet.vehicleId);
            }
        });
        context.setPacketHandled(true);
    }
}
