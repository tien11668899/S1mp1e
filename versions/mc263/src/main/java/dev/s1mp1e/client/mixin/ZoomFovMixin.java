package dev.s1mp1e.client.mixin;

import dev.s1mp1e.client.module.ZoomModule;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Zoom (26.2): scales the final rendered FOV. mc1211 hooked {@code GameRenderer.getFov(Camera, float,
 * boolean)}, which served both the world projection ({@code changingFov=true}) and the first-person hand
 * projection ({@code false}). In 26.2 FOV moved into {@link Camera#update}: {@code calculateFov(F)F} feeds
 * the world perspective (+ culling, which takes max(fov, option fov) so a narrower zoom never culls
 * wrongly) and {@code calculateHudFov(F)F} feeds {@code CameraRenderState.hudFov}, used only for the hand /
 * item-in-hand projection. Both are multiplied at RETURN, matching mc1211 where both paths went through the
 * hooked method. Render-only.
 */
@Mixin(Camera.class)
public class ZoomFovMixin {

    @Inject(method = "calculateFov(F)F", at = @At("RETURN"), cancellable = true)
    private void s1mp1e$zoom(float partialTicks, CallbackInfoReturnable<Float> cir) {
        // Advance + read the eased factor once per camera update; 1.0 when fully released (no-op).
        double f = ZoomModule.smoothFactor();
        if (f < 0.999) {
            cir.setReturnValue((float) (cir.getReturnValueF() * f));
        }
    }

    @Inject(method = "calculateHudFov(F)F", at = @At("RETURN"), cancellable = true)
    private void s1mp1e$zoomHud(float partialTicks, CallbackInfoReturnable<Float> cir) {
        // calculateHudFov runs right after calculateFov in Camera.update; reuse the factor just advanced.
        double f = ZoomModule.lookScale();
        if (f < 0.999) {
            cir.setReturnValue((float) (cir.getReturnValueF() * f));
        }
    }
}
