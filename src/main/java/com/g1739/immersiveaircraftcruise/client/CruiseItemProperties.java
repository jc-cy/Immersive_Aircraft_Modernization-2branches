package com.g1739.immersiveaircraftcruise.client;

import com.g1739.immersiveaircraftcruise.CruiseItems;
import com.g1739.immersiveaircraftcruise.ImmersiveAircraftCruise;
import com.g1739.immersiveaircraftcruise.cruise.CruiseModuleData;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;

public final class CruiseItemProperties {
    private CruiseItemProperties() {
    }

    public static void register() {
        ItemProperties.register(CruiseItems.CRUISE_MODULE.get(),
                new ResourceLocation(ImmersiveAircraftCruise.MOD_ID, "boosting"),
                (stack, level, entity, seed) -> stack.hasTag() && stack.getTag().getBoolean(CruiseModuleData.BOOSTING_TAG) ? 1.0f : 0.0f);
    }
}
