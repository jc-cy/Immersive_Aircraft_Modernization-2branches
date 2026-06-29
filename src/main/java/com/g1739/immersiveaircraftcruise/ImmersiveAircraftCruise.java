package com.g1739.immersiveaircraftcruise;

import com.g1739.immersiveaircraftcruise.network.CruiseNetwork;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(ImmersiveAircraftCruise.MOD_ID)
public class ImmersiveAircraftCruise {
    public static final String MOD_ID = "immersive_aircraft_cruise";
    public static final Logger LOGGER = LogUtils.getLogger();

    public ImmersiveAircraftCruise(IEventBus modBus) {
        CruiseItems.register(modBus);
        CruiseTabs.register(modBus);
        CruiseNetwork.register();
    }
}
