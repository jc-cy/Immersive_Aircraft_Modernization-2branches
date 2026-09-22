package com.g1739.immersiveaircraftcruise.network;


import com.g1739.immersiveaircraftcruise.client.ClientPacketHandlers;
import com.g1739.immersiveaircraftcruise.cruise.CruiseRoute;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record OpenCruiseScreenPacket(int entityId, CruiseRoute route, boolean readOnly,
                                     boolean decelerateWhenChunksNotReady) implements CustomPacketPayload {
    public static final Type<OpenCruiseScreenPacket> TYPE = CruiseNetwork.type("open_cruise_screen");
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenCruiseScreenPacket> STREAM_CODEC = StreamCodec.ofMember(OpenCruiseScreenPacket::encode, OpenCruiseScreenPacket::decode);

    public OpenCruiseScreenPacket(int entityId, CruiseRoute route) {
        this(entityId, route, false, false);
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeInt(entityId);
        route.write(buffer);
        buffer.writeBoolean(readOnly);
        buffer.writeBoolean(decelerateWhenChunksNotReady);
    }

    public static OpenCruiseScreenPacket decode(FriendlyByteBuf buffer) {
        return new OpenCruiseScreenPacket(buffer.readInt(), CruiseRoute.read(buffer),
                buffer.readBoolean(), buffer.readBoolean());
    }

    @Override
    public Type<OpenCruiseScreenPacket> type() {
        return TYPE;
    }

    public static void handle(OpenCruiseScreenPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> ClientPacketHandlers.openCruiseScreen(packet.entityId, packet.route,
                packet.readOnly, packet.decelerateWhenChunksNotReady));
    }
}
