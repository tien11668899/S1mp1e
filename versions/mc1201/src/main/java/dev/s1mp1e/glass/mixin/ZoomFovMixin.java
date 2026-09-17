package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.module.ZoomModule;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Zoom: scales the final rendered FOV. {@code GameRenderer.getFov(Camera, float, boolean)}
 * (method_3196, disambiguated from the no-arg {@code getFov()} option accessor by its full
 * descriptor) returns the FOV in degrees; while the zoom key is held we multiply it by the
 * configured factor at RETURN. Render-only.
 */
@Mixin(GameRenderer.class)
public class ZoomFovMixin {

    @Inject(method = "getFov(Lnet/minecraft/client/render/Camera;FZ)D",
            at = @At("RETURN"), cancellable = true)
    private void s1mp1e$zoom(CallbackInfoReturnable<Double> cir) {
        // Advance + read the eased factor every frame; 1.0 when fully released (no-op).
        double f = ZoomModule.smoothFactor();
        if (f < 0.999) {
            cir.setReturnValue(cir.getReturnValueD() * f);
        }
    }
}
