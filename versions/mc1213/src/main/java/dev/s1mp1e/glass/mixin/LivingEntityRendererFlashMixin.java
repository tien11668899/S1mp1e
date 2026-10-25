package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.module.NoHurtCamModule;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * NoHurtCam (red flash): suppresses the red hurt/death overlay tint. {@code
 * LivingEntityRenderer.getOverlay(LivingEntity, float)} (method_23622, STATIC, verified in
 * yarn 1.21.1+build.3) normally returns {@code OverlayTexture.getUv(progress, entity is
 * hurt)}; when the setting is on and the entity is flashing we return the same UV with the
 * hurt flag FALSE, so the white damage flash keeps its shape but the red tint is gone.
 * Cosmetic only (the 1.21.1 equivalent of 1.8.9's RendererLivingEntity.setBrightness).
 */
@Mixin(LivingEntityRenderer.class)
public class LivingEntityRendererFlashMixin {
    @Inject(method = "getOverlay", at = @At("HEAD"), cancellable = true)
    private static void s1mp1e$noHurtFlash(LivingEntityRenderState state, float whiteOverlayProgress,
                                           CallbackInfoReturnable<Integer> cir) {
        // 1.21.2+: a render state; its {@code hurt} is set while hurtTime > 0 or deathTime > 0
        if (NoHurtCamModule.flashSuppressed() && state != null && state.hurt) {
            cir.setReturnValue(OverlayTexture.getUv(whiteOverlayProgress, false));
        }
    }
}
