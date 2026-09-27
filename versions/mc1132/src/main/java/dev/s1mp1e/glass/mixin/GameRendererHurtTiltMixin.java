package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.module.NoHurtCamModule;
import net.minecraft.class_4218;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * NoHurtCam (camera shake), 1.13.2 port: cancels the hurt-tilt transform of the UNMAPPED game renderer
 * {@code net.minecraft.class_4218} — the private {@code method_19081(F)V} (bobViewWhenHurt; it reads
 * {@code hurtTime/deathTime/maxHurtTime} and issues {@code GlStateManager.rotate}s; javap-verified,
 * legacy yarn 1.13.2+build.604-v2; the walk bob is the separate {@code method_19083(F)V}) — at HEAD when
 * the setting is on. It only issues GL rotations, so skipping it leaves the matrix stack balanced.
 * Cosmetic only.
 */
@Mixin(class_4218.class)
public class GameRendererHurtTiltMixin {

    @Inject(method = "method_19081(F)V", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$noHurtTilt(float tickDelta, CallbackInfo ci) {
        try {
            if (NoHurtCamModule.shakeSuppressed()) ci.cancel();
        } catch (Throwable ignored) {
            // vanilla tilt stands on any failure
        }
    }
}
