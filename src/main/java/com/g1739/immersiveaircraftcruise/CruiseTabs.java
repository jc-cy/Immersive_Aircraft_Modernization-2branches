package com.g1739.immersiveaircraftcruise;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class CruiseTabs {
    private static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ImmersiveAircraftCruise.MOD_ID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN = TABS.register("main", () -> CreativeModeTab.builder()
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
