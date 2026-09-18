package com.g1739.immersiveaircraftcruise;

import com.g1739.immersiveaircraftcruise.cruise.CruiseChunkSendScheduler;
import com.g1739.immersiveaircraftcruise.cruise.CruiseConfig;
import com.g1739.immersiveaircraftcruise.cruise.CruiseController;
import com.g1739.immersiveaircraftcruise.network.CruiseNetwork;
import com.mojang.logging.LogUtils;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(ImmersiveAircraftCruise.MOD_ID)
public class ImmersiveAircraftCruise {
    public static final String MOD_ID = "immersive_aircraft_cruise";
    public static final Logger LOGGER = LogUtils.getLogger();

    public ImmersiveAircraftCruise() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, CruiseConfig.SPEC,
                MOD_ID + "-common.toml");
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        CruiseItems.register(modBus);
        CruiseTabs.register(modBus);
        CruiseNetwork.register();
        CruiseController.register();
        CruiseChunkSendScheduler.register();
    }
}
