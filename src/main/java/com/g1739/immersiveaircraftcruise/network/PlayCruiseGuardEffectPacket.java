package com.g1739.immersiveaircraftcruise.network;

import com.g1739.immersiveaircraftcruise.client.ClientPacketHandlers;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server to client: play the vanilla item activation animation (the "totem of undying" animation)
 * on this rider, with the cruise module as the animated item.
 */
public record PlayCruiseGuardEffectPacket(int entityId) {
    public void encode(FriendlyByteBuf buffer) {
        buffer.writeInt(entityId);
    }

    public static PlayCruiseGuardEffectPacket decode(FriendlyByteBuf buffer) {
        return new PlayCruiseGuardEffectPacket(buffer.readInt());
    }

    public static void handle(PlayCruiseGuardEffectPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientPacketHandlers.playGuardEffect(packet.entityId)));
        context.setPacketHandled(true);
    }
}
