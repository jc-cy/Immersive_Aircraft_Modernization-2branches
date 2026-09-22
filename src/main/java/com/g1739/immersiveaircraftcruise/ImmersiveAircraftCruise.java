package com.g1739.immersiveaircraftcruise;

import com.g1739.immersiveaircraftcruise.cruise.CruiseChunkSendScheduler;
import com.g1739.immersiveaircraftcruise.cruise.CruiseConfig;
import com.g1739.immersiveaircraftcruise.cruise.CruiseController;
import com.g1739.immersiveaircraftcruise.network.CruiseNetwork;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.slf4j.Logger;

@Mod(ImmersiveAircraftCruise.MOD_ID)
public class ImmersiveAircraftCruise {
    public static final String MOD_ID = "immersive_aircraft_cruise";
    public static final Logger LOGGER = LogUtils.getLogger();

    public ImmersiveAircraftCruise(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, CruiseConfig.SPEC,
                MOD_ID + "-common.toml");
        CruiseItems.register(modBus);
        CruiseTabs.register(modBus);
        CruiseNetwork.register();
        CruiseController.register();
        CruiseChunkSendScheduler.register();
    }
}
