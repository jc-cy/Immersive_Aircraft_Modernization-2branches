package com.g1739.immersiveaircraftcruise.network;

import com.g1739.immersiveaircraftcruise.client.ClientPacketHandlers;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server to client: play the vanilla item activation animation (the "totem of undying" animation)
 * on this rider, with the cruise module as the animated item.
 */
public record PlayCruiseGuardEffectPacket(int entityId) implements CustomPacketPayload {
    public static final Type<PlayCruiseGuardEffectPacket> TYPE = CruiseNetwork.type("play_cruise_guard_effect");
    public static final StreamCodec<RegistryFriendlyByteBuf, PlayCruiseGuardEffectPacket> STREAM_CODEC =
            StreamCodec.ofMember(PlayCruiseGuardEffectPacket::encode, PlayCruiseGuardEffectPacket::decode);

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeInt(entityId);
    }

    public static PlayCruiseGuardEffectPacket decode(FriendlyByteBuf buffer) {
        return new PlayCruiseGuardEffectPacket(buffer.readInt());
    }

    @Override
    public Type<PlayCruiseGuardEffectPacket> type() {
        return TYPE;
    }

    public static void handle(PlayCruiseGuardEffectPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> ClientPacketHandlers.playGuardEffect(packet.entityId));
    }
}
