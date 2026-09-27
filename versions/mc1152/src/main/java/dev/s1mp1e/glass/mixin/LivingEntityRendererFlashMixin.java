package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.module.NoHurtCamModule;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * NoHurtCam (red flash), 1.15.2. The 1.14.4 fixed-function {@code applyOverlayColor} no longer exists;
 * 1.15.2 encodes the red hurt/death tint in the overlay-texture UV returned by the STATIC
 * {@code LivingEntityRenderer.getOverlay(LivingEntity, float)I} (public; javap-verified, yarn
 * 1.15.2+build.17):
 *
 * <pre>
 *   return OverlayTexture.packUv(OverlayTexture.getU(whiteOverlayProgress),
 *                                OverlayTexture.getV(entity.hurtTime &gt; 0 || entity.deathTime &gt; 0));
 * </pre>
 *
 * When the setting is on and the entity is flashing red we return the same UV with the hurt flag
 * FALSE ({@code OverlayTexture.getUv(F,Z)I} = {@code packUv(getU(f), getV(z))}), so the white
 * (creeper-swell) flash keeps working but the red tint is gone. The entity's real fields are never
 * written. Same form as the 1.16.5 port. Cosmetic only.
 */
@Mixin(LivingEntityRenderer.class)
public class LivingEntityRendererFlashMixin {

    @Inject(method = "getOverlay(Lnet/minecraft/entity/LivingEntity;F)I",
            at = @At("HEAD"), cancellable = true)
    private static void s1mp1e$noHurtFlash(LivingEntity entity, float whiteOverlayProgress,
                                           CallbackInfoReturnable<Integer> cir) {
        try {
            if (NoHurtCamModule.flashSuppressed() && entity != null
                    && (entity.hurtTime > 0 || entity.deathTime > 0)) {
                cir.setReturnValue(OverlayTexture.getUv(whiteOverlayProgress, false));
            }
        } catch (Throwable ignored) {
            // vanilla overlay UV (red flash) stands on any failure
        }
    }
}
