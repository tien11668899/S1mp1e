package com.seagull.liquidglass.client.compat.essential.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * S1mp1e draws no text shadows anywhere — Essential 1.5 / Elementa 774 text included. Elementa 774 extracts text
 * through {@code FontProviderKt.extractMcScale(..., boolean shadow, Color shadowColor)} (UIText, UIWrappedText and many
 * Essential components call it directly), not the old {@code draw(UMatrixStack)} path {@link EssTextShadowMixin}
 * covers, so the shadow flag is cleared at that single entry point.
 */
@Pseudo
@Mixin(targets = "gg.essential.elementa.font.FontProviderKt")   // string: absent on Essential 1.4 / Elementa 745
public abstract class EssFontShadowMixin {

   @ModifyVariable(method = "extractMcScale", at = @At("HEAD"), argsOnly = true, ordinal = 0)
   private static boolean lg$noShadow(boolean shadow) {
      return false;
   }
}
