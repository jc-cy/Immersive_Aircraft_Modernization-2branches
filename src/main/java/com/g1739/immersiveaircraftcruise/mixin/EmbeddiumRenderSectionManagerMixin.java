package com.g1739.immersiveaircraftcruise.mixin;


import com.g1739.immersiveaircraftcruise.client.CruiseRenderPriority;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Promotes only the forward route corridor in Embeddium's section rebuild queues.
 *
 * <p>The class name moved between 1.20 Embeddium and the 1.21 (Sodium 0.6 based) line, so both are
 * listed. {@code @Pseudo} plus {@code require = 0} keeps this optional: when no candidate class is
 * present the mixin is simply skipped.
 */
@Pseudo
@Mixin(targets = {
        "me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager",
        "org.embeddedt.embeddium.impl.render.chunk.RenderSectionManager"
}, remap = false)
public abstract class EmbeddiumRenderSectionManagerMixin {
    private static final ThreadLocal<Boolean> PROMOTING_ROUTE_REBUILD =
            ThreadLocal.withInitial(() -> false);

    @Inject(method = "scheduleRebuild", at = @At("RETURN"), require = 0)
    private void iacruise$promoteRouteRebuild(int sectionX, int sectionY, int sectionZ, boolean important,
                                               CallbackInfo ci) {
        if (important || PROMOTING_ROUTE_REBUILD.get()
                || Thread.currentThread() != renderThread
                || !CruiseRenderPriority.isForwardRouteSection(sectionX, sectionZ)) {
            return;
        }
        PROMOTING_ROUTE_REBUILD.set(true);
        try {
            scheduleRebuild(sectionX, sectionY, sectionZ, true);
        } finally {
            PROMOTING_ROUTE_REBUILD.set(false);
        }
    }

    @Inject(method = "onSectionAdded", at = @At("TAIL"), require = 0)
    private void iacruise$prioritizeRouteInitialBuild(int sectionX, int sectionY, int sectionZ, CallbackInfo ci) {
        if (CruiseRenderPriority.isForwardRouteSection(sectionX, sectionZ)) {
            scheduleRebuild(sectionX, sectionY, sectionZ, true);
        }
    }

    @Shadow
    @Final
    private Thread renderThread;

    @Shadow
    public abstract void scheduleRebuild(int sectionX, int sectionY, int sectionZ, boolean important);
}
