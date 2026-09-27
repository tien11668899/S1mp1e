package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.module.NoHurtCamModule;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * NoHurtCam (camera shake): cancels {@code GameRenderer.bobViewWhenHurt(MatrixStack, float)}
 * (the 1.16.5 hurt-tilt transform, verified private on yarn 1.16.5+build.10 — the equivalent of
 * 1.21.1's {@code tiltViewWhenHurt}) at HEAD when the setting is on, skipping the whole hurt-tilt
 * transform. Cosmetic only.
 */
@Mixin(GameRenderer.class)
public class GameRendererHurtTiltMixin {
    @Inject(method = "bobViewWhenHurt", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$noHurtTilt(MatrixStack matrices, float tickDelta, CallbackInfo ci) {
        try {
            if (NoHurtCamModule.shakeSuppressed()) ci.cancel();
        } catch (Throwable t) {
            // Any failure -> leave the vanilla tilt in place.
        }
    }
}
