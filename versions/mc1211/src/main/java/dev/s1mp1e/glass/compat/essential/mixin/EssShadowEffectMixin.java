package dev.s1mp1e.glass.compat.essential.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Essential's own {@code ShadowEffect} paints a 1 px offset copy of its text ({@code EssentialUIText}) and icons in a
 * dark shadow colour before the component draws. On the glass side menu that dark copy reads as a black outline and
 * makes the labels look eaten on bright backdrops — and S1mp1e draws no text shadows. The effect only paints that
 * shadow pass, so it is skipped entirely (extract path for Essential 1.5, draw path for 1.4).
 */
@Pseudo
@Mixin(targets = "gg.essential.gui.common.shadow.ShadowEffect")
public abstract class EssShadowEffectMixin {

   @Inject(method = "extractBefore", at = @At("HEAD"), cancellable = true)
   private void lg$noShadowExtract(@Coerce Object extractor, CallbackInfo ci) {
      ci.cancel();
   }

   @Inject(method = "beforeDraw", at = @At("HEAD"), cancellable = true)
   private void lg$noShadowDraw(@Coerce Object matrixStack, CallbackInfo ci) {
      ci.cancel();
   }
}
