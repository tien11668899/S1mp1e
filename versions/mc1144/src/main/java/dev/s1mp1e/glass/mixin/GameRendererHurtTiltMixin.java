package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.module.NoHurtCamModule;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * NoHurtCam (camera shake): cancels {@code GameRenderer.bobViewWhenHurt(F)V} (private on 1.14.4 —
 * javap-verified, yarn 1.14.4+build.18; the 1.14.4 name of 1.21.1's {@code tiltViewWhenHurt}) at HEAD
 * when the setting is on, skipping the whole hurt-tilt transform. It only issues GL rotations, so
 * skipping it leaves the matrix stack balanced. Cosmetic only.
 */
@Mixin(GameRenderer.class)
public class GameRendererHurtTiltMixin {

    @Inject(method = "bobViewWhenHurt(F)V", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$noHurtTilt(float tickDelta, CallbackInfo ci) {
        try {
            if (NoHurtCamModule.shakeSuppressed()) ci.cancel();
        } catch (Throwable ignored) {
            // vanilla tilt stands on any failure
        }
    }
}
