package com.g1739.immersiveaircraftcruise.item;

import com.g1739.immersiveaircraftcruise.cruise.CruiseModuleData;
import com.g1739.immersiveaircraftcruise.cruise.CruiseController;
import com.g1739.immersiveaircraftcruise.network.CruiseNetwork;
import com.g1739.immersiveaircraftcruise.network.OpenCruiseScreenPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class CruiseModuleItem extends Item {
    public CruiseModuleItem(Properties properties) {
        super(properties);
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slotId, boolean isSelected) {
        if (!level.isClientSide() && stack.hasTag()) {
            stack.getTag().remove(CruiseModuleData.BOOSTING_TAG);
        }
        super.inventoryTick(stack, level, entity, slotId, isSelected);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide()) {
            return InteractionResultHolder.sidedSuccess(stack, true);
        }
        if (player instanceof ServerPlayer serverPlayer) {
            CruiseNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> serverPlayer),
                    new OpenCruiseScreenPacket(-1, CruiseModuleData.read(stack), false,
                            CruiseController.preloadAutoDeceleration()));
            return InteractionResultHolder.success(stack);
        }
        return InteractionResultHolder.fail(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.immersive_aircraft_cruise.category").withStyle(ChatFormatting.BLUE));
        tooltip.add(Component.translatable("tooltip.immersive_aircraft_cruise.cruise_module_quote"));
        tooltip.add(Component.translatable("tooltip.immersive_aircraft_cruise.cruise_module",
                Component.keybind("key.immersive_aircraft_cruise.open_cruise"),
                Component.keybind("key.immersive_aircraft_cruise.toggle_cruise")));
        tooltip.add(Component.translatable("tooltip.immersive_aircraft_cruise.cruise_module_boost",
                Component.translatable("tooltip.immersive_aircraft_cruise.overclock_mode").withStyle(ChatFormatting.GOLD)));
        tooltip.add(Component.translatable("tooltip.immersive_aircraft_cruise.cruise_module_refresh",
                Component.keybind("key.immersive_aircraft_cruise.refresh_ride")));
        super.appendHoverText(stack, level, tooltip, flag);
    }
}
