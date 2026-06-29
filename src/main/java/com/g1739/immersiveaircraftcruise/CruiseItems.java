package com.g1739.immersiveaircraftcruise;

import com.g1739.immersiveaircraftcruise.item.CruiseModuleItem;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class CruiseItems {
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(BuiltInRegistries.ITEM, ImmersiveAircraftCruise.MOD_ID);

    public static final DeferredHolder<Item, Item> CRUISE_MODULE = ITEMS.register("cruise_module",
            () -> new CruiseModuleItem(new Item.Properties().stacksTo(8).rarity(Rarity.EPIC)));

    private CruiseItems() {
    }

    public static void register(IEventBus bus) {
        ITEMS.register(bus);
    }
}
