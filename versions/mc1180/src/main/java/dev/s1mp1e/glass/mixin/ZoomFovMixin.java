package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.module.ZoomModule;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Zoom: scales the final rendered FOV. {@code GameRenderer.getFov(Camera, float, boolean)} (private,
 * method_3196, javap-verified in yarn 1.18.2+build.4; pinned by its full descriptor) returns the FOV in
 * degrees; while the zoom key is held we multiply it by the eased factor at RETURN. Render-only.
 *
 * <p>Zoomify (1.6.0 on this line) may also hook this RETURN (a MixinExtras {@code @ModifyReturnValue}); both read the current
 * return value, so the two compose, and "Block other zoom" keeps Zoomify's own factor at 1.
 */
@Mixin(GameRenderer.class)
public class ZoomFovMixin {

    @Inject(method = "getFov(Lnet/minecraft/client/render/Camera;FZ)D",
            at = @At("RETURN"), cancellable = true)
    private void s1mp1e$zoom(CallbackInfoReturnable<Double> cir) {
        try {
            // Advance + read the eased factor every frame; 1.0 when fully released (no-op).
            double f = ZoomModule.smoothFactor();
            if (f < 0.999) {
                cir.setReturnValue(cir.getReturnValueD() * f);
            }
        } catch (Throwable ignored) {
            // vanilla FOV on failure
        }
    }
}
