package dev.s1mp1e.client.mixin;

import dev.s1mp1e.client.module.ZoomModule;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Zoom on 1.21.6-1.21.8: both field-of-view values come from {@code GameRenderer.getFov(Camera, float, boolean)} — the
 * world projection with {@code useFovSetting} true, the first-person hand with false (26.x splits these into
 * {@code Camera.calculateFov} / {@code calculateHudFov}). The world call advances the eased zoom factor; the hand call
 * reuses it.
 */
@Mixin(GameRenderer.class)
public class ZoomFovMixin {
    @Inject(method = "getFov(Lnet/minecraft/client/Camera;FZ)F", at = @At("RETURN"), cancellable = true)
    private void s1mp1e$zoom(Camera camera, float partialTicks, boolean useFovSetting, CallbackInfoReturnable<Float> cir) {
        double f = useFovSetting ? ZoomModule.smoothFactor() : ZoomModule.lookScale();
        if (f < 0.999) {
            cir.setReturnValue((float) (cir.getReturnValueF() * f));
        }
    }
}
