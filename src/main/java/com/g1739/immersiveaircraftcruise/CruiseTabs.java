package com.g1739.immersiveaircraftcruise;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public final class CruiseTabs {
    private static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ImmersiveAircraftCruise.MOD_ID);

    public static final RegistryObject<CreativeModeTab> MAIN = TABS.register("main", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.immersive_aircraft_cruise.main"))
            .icon(() -> CruiseItems.CRUISE_MODULE.get().getDefaultInstance())
            .displayItems((parameters, output) -> output.accept(CruiseItems.CRUISE_MODULE.get()))
            .build());

    private CruiseTabs() {
    }

    public static void register(IEventBus bus) {
        TABS.register(bus);
    }
}
