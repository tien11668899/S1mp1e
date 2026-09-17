package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.module.NoHurtCamModule;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * NoHurtCam (camera shake): cancels {@code GameRenderer.tiltViewWhenHurt} (method_3198,
 * verified in yarn 1.21.1+build.3 — the 1.21.1 equivalent of 1.8.9's
 * {@code EntityRenderer.hurtCameraEffect}) at HEAD when the setting is on, skipping the
 * whole hurt-tilt transform. Cosmetic only.
 */
@Mixin(GameRenderer.class)
public class GameRendererHurtTiltMixin {
    @Inject(method = "tiltViewWhenHurt", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$noHurtTilt(MatrixStack matrices, float tickDelta, CallbackInfo ci) {
        if (NoHurtCamModule.shakeSuppressed()) ci.cancel();
    }
}
