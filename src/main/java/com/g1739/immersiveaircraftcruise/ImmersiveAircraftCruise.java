package com.g1739.immersiveaircraftcruise;

import com.g1739.immersiveaircraftcruise.network.CruiseNetwork;
import com.mojang.logging.LogUtils;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(ImmersiveAircraftCruise.MOD_ID)
public class ImmersiveAircraftCruise {
    public static final String MOD_ID = "immersive_aircraft_cruise";
    public static final Logger LOGGER = LogUtils.getLogger();

    public ImmersiveAircraftCruise() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        CruiseItems.register(modBus);
        CruiseTabs.register(modBus);
        CruiseNetwork.register();
    }
}
