package com.g1739.immersiveaircraftcruise.client;

import com.g1739.immersiveaircraftcruise.cruise.CruiseImpactGuard;
import com.g1739.immersiveaircraftcruise.cruise.CruiseModuleData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.client.IItemDecorator;

/**
 * Draws the impact protection cooldown on the module icon through NeoForge's standard item
 * decorator hook. The progress is derived from the module's own NBT (server-authoritative deadline)
 * and the client world clock, so every viewer sees the same remaining time on the same stack - no
 * client cooldown table, no per-tick scanning, and only the cooling module shows an overlay.
 *
 * <p>The painting mirrors vanilla's cooldown overlay pixels exactly (a translucent white wipe that
 * shrinks from the bottom up).
 */
public final class CruiseModuleGuardDecorator implements IItemDecorator {
    private static final int OVERLAY_COLOR = 0x7FFFFFFF;
    private static final float ICON_SIZE = 16.0f;

    @Override
    public boolean render(GuiGraphics guiGraphics, Font font, ItemStack stack, int x, int y) {
        long end = CruiseModuleData.guardCooldownEnd(stack);
        if (end <= 0L) {
            return false;
        }
        Level level = Minecraft.getInstance().level;
        if (level == null) {
            return false;
        }
        long now = level.getGameTime();
        if (now >= end) {
            return false;
        }
        float remaining = Mth.clamp((end - now) / (float) CruiseImpactGuard.COOLDOWN_TICKS, 0.0f, 1.0f);
        if (remaining <= 0.0f) {
            return false;
        }
        int top = y + Mth.floor(ICON_SIZE * (1.0f - remaining));
        int bottom = top + Mth.ceil(ICON_SIZE * remaining);
        guiGraphics.fill(RenderType.guiOverlay(), x, top, x + (int) ICON_SIZE, bottom, OVERLAY_COLOR);
        return false;
    }
}
