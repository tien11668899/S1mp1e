package dev.s1mp1e.client.mixin;

import dev.s1mp1e.client.module.NoHurtCamModule;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * NoHurtCam (red flash): suppresses the red hurt/death overlay tint. In 26.2 the entity is gone by
 * render time: {@code LivingEntityRenderer.extractRenderState} sets
 * {@code state.hasRedOverlay = hurtTime > 0 || deathTime > 0}, and the STATIC
 * {@code LivingEntityRenderer.getOverlayCoords(LivingEntityRenderState, float)} (javap-verified; the
 * 26.2 successor of 1.21.1 yarn {@code getOverlay(LivingEntity, float)}) returns
 * {@code OverlayTexture.pack(u(whiteOverlayProgress), v(state.hasRedOverlay))}. When the setting is on and
 * the state is flashing we return the same UV with the red flag FALSE, so the white overlay keeps its
 * shape but the red tint is gone — identical to the mc1211 behaviour. The render state is not
 * mutated. Cosmetic only.
 */
@Mixin(LivingEntityRenderer.class)
public class LivingEntityRendererFlashMixin {
    @Inject(method = "getOverlayCoords", at = @At("HEAD"), cancellable = true)
    private static void s1mp1e$noHurtFlash(LivingEntityRenderState state, float whiteOverlayProgress,
                                           CallbackInfoReturnable<Integer> cir) {
        if (NoHurtCamModule.flashSuppressed() && state != null && state.hasRedOverlay) {
            cir.setReturnValue(OverlayTexture.pack(OverlayTexture.u(whiteOverlayProgress), OverlayTexture.v(false)));
        }
    }
}
