package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.module.ZoomModule;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Zoom: scales the final rendered FOV. {@code GameRenderer.getFov(Camera, float, boolean)}
 * (private on 1.14.4, same descriptor as 1.21.1 — javap-verified, yarn 1.14.4+build.18) returns
 * the FOV in degrees; while the zoom key is held we multiply it by the eased factor at RETURN.
 * Render-only.
 */
@Mixin(GameRenderer.class)
public class ZoomFovMixin {

    @Unique private static boolean s1mp1e$failed;

    @Inject(method = "getFov(Lnet/minecraft/client/render/Camera;FZ)D",
            at = @At("RETURN"), cancellable = true)
    private void s1mp1e$zoom(CallbackInfoReturnable<Double> cir) {
        if (s1mp1e$failed) return;
        try {
            // Advance + read the eased factor every frame; 1.0 when fully released (no-op).
            double f = ZoomModule.smoothFactor();
            if (f < 0.999) {
                cir.setReturnValue(cir.getReturnValueD() * f);
            }
        } catch (Throwable t) {
            s1mp1e$failed = true;
            System.out.println("[S1mp1e] Zoom: FOV hook disabled after error: " + t);
        }
    }
}
