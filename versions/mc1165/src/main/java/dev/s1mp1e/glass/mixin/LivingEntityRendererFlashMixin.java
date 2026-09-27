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
 * NoHurtCam (red flash): suppresses the red hurt/death overlay tint. {@code
 * LivingEntityRenderer.getOverlay(LivingEntity, float)} (STATIC, verified on yarn 1.16.5+build.10)
 * normally returns {@code OverlayTexture.getUv(progress, entity is hurt)}; when the setting is on and
 * the entity is flashing we return the same UV with the hurt flag FALSE, so the white damage flash
 * keeps its shape but the red tint is gone. Cosmetic only.
 */
@Mixin(LivingEntityRenderer.class)
public class LivingEntityRendererFlashMixin {
    @Inject(method = "getOverlay", at = @At("HEAD"), cancellable = true)
    private static void s1mp1e$noHurtFlash(LivingEntity entity, float whiteOverlayProgress,
                                           CallbackInfoReturnable<Integer> cir) {
        try {
            if (NoHurtCamModule.flashSuppressed() && entity != null
                    && (entity.hurtTime > 0 || entity.deathTime > 0)) {
                cir.setReturnValue(OverlayTexture.getUv(whiteOverlayProgress, false));
            }
        } catch (Throwable t) {
            // Any failure -> leave the vanilla overlay UV untouched.
        }
    }
}
