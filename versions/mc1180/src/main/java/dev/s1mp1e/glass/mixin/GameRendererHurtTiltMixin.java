package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.module.NoHurtCamModule;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * NoHurtCam (camera shake): cancels the hurt-camera tilt at HEAD when the setting is on, skipping the
 * whole transform. Cosmetic only.
 *
 * <p>1.18.2 port: yarn 1.18.2+build.4 names it {@code private void bobViewWhenHurt(MatrixStack, float)}
 * (method_3198, javap-verified); the 1.20+ name {@code tiltViewWhenHurt} does not exist here and would
 * crash GameRenderer loading under {@code defaultRequire 1}. The method only pushes rotations onto the
 * passed MatrixStack (no push/pop of its own), so cancelling it leaves the stack balanced.
 */
@Mixin(GameRenderer.class)
public class GameRendererHurtTiltMixin {
    @Inject(method = "bobViewWhenHurt", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$noHurtTilt(MatrixStack matrices, float tickDelta, CallbackInfo ci) {
        try {
            if (NoHurtCamModule.shakeSuppressed()) ci.cancel();
        } catch (Throwable ignored) {
            // vanilla tilt on failure
        }
    }
}
