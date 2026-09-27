package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.module.NoHurtCamModule;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * NoHurtCam (camera shake), 1.15.2: cancels {@code GameRenderer.bobViewWhenHurt(MatrixStack, float)}
 * (private; javap-verified, yarn 1.15.2+build.17 — the 1.15.2 name of 1.21.1's {@code tiltViewWhenHurt})
 * at HEAD when the setting is on, skipping the whole hurt-tilt transform. On 1.15.2 world rendering
 * already takes a {@code MatrixStack}, so the tilt is a pair of {@code matrices.multiply} rotations; it
 * is invoked from three sites (the hand pass and the world/camera pass), and skipping it at HEAD pushes
 * nothing, so every caller's stack stays balanced. Cosmetic only.
 */
@Mixin(GameRenderer.class)
public class GameRendererHurtTiltMixin {

    @Inject(method = "bobViewWhenHurt(Lnet/minecraft/client/util/math/MatrixStack;F)V",
            at = @At("HEAD"), cancellable = true)
    private void s1mp1e$noHurtTilt(MatrixStack matrices, float tickDelta, CallbackInfo ci) {
        try {
            if (NoHurtCamModule.shakeSuppressed()) ci.cancel();
        } catch (Throwable ignored) {
            // vanilla tilt stands on any failure
        }
    }
}
