package com.g1739.immersiveaircraftcruise;

import com.g1739.immersiveaircraftcruise.item.CruiseModuleItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class CruiseItems {
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, ImmersiveAircraftCruise.MOD_ID);

    public static final RegistryObject<Item> CRUISE_MODULE = ITEMS.register("cruise_module",
            () -> new CruiseModuleItem(new Item.Properties().stacksTo(8)));

    private CruiseItems() {
    }

    public static void register(IEventBus bus) {
        ITEMS.register(bus);
    }
}
