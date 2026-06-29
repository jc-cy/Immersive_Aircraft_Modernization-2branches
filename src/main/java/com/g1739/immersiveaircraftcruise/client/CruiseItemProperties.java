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
                ResourceLocation.fromNamespaceAndPath(ImmersiveAircraftCruise.MOD_ID, "boosting"),
                (stack, level, entity, seed) -> CruiseModuleData.isBoosting(stack) ? 1.0f : 0.0f);
    }
}
