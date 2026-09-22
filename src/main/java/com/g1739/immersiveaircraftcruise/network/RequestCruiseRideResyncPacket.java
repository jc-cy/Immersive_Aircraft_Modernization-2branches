package com.g1739.immersiveaircraftcruise.network;


import com.g1739.immersiveaircraftcruise.cruise.CruiseChunkSendScheduler;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Sent when the client's own riding state disagrees with the server's 5-tick onboard heartbeat
 * ({@code UpdateCruiseFuelPacket} already carries the aircraft id it is sent for).
 *
 * <p>Two mismatches are possible: the server counts the player onboard while the client lost the
 * aircraft or its attachment, or the client still rides an aircraft the server no longer counts it
 * on. The server decides what to do, so the client never guesses.
 *
 * <p>The request also carries the difference between the two ways of repairing: the automatic drift
 * check asks for the cheap alignment (packets only, no entity churn), while the reset key asks for a
 * hard reset that re-creates this client's copy of the aircraft from server state.
 *
 * <p>It also reports whether the sending client believes it is the aircraft's control seat. The two
 * sides can disagree (a pilot who left hands the seat to the remaining rider, and the client may still
 * be flying a copy of the old arrangement), and a disagreement means this client is not the authority
 * for the aircraft's position - which is what decides whether a rebuild is safe.
 */
public class RequestCruiseRideResyncPacket implements CustomPacketPayload {
    public static final Type<RequestCruiseRideResyncPacket> TYPE = CruiseNetwork.type("request_cruise_ride_resync");
    public static final StreamCodec<RegistryFriendlyByteBuf, RequestCruiseRideResyncPacket> STREAM_CODEC = StreamCodec.ofMember(RequestCruiseRideResyncPacket::encode, RequestCruiseRideResyncPacket::decode);

    private final int vehicleId;
    private final boolean hardReset;
    private final boolean clientControls;

    public RequestCruiseRideResyncPacket(int vehicleId, boolean hardReset, boolean clientControls) {
        this.vehicleId = vehicleId;
        this.hardReset = hardReset;
        this.clientControls = clientControls;
    }

    public void encode(RegistryFriendlyByteBuf buffer) {
        buffer.writeInt(vehicleId);
        buffer.writeBoolean(hardReset);
        buffer.writeBoolean(clientControls);
    }

    public static RequestCruiseRideResyncPacket decode(RegistryFriendlyByteBuf buffer) {
        return new RequestCruiseRideResyncPacket(buffer.readInt(), buffer.readBoolean(), buffer.readBoolean());
    }

    @Override
    public Type<RequestCruiseRideResyncPacket> type() {
        return TYPE;
    }

    public static void handle(RequestCruiseRideResyncPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (packet.hardReset) {
                CruiseChunkSendScheduler.hardResetRideState(player, packet.vehicleId,
                        packet.clientControls);
            } else {
                CruiseChunkSendScheduler.repairRideState(player, packet.vehicleId);
            }
        });
    }
}
