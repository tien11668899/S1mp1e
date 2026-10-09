package com.seagull.liquidglass.client.compat.essential.mixin;

import com.seagull.liquidglass.client.compat.essential.EssentialLang;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * {@code EssentialUIText} keeps its own copy of the full string ({@code fullText}) and truncates it with "..." itself —
 * translate that copy too, or the truncation would run on (and show) the English.
 */
@Pseudo
@Mixin(targets = "gg.essential.gui.common.shadow.EssentialUIText")
public abstract class EssTruncatingTextMixin {

   @ModifyArg(method = "<init>(Ljava/lang/String;ZLjava/awt/Color;ZZZZ)V",
              at = @At(value = "INVOKE", target = "Lgg/essential/elementa/state/BasicState;<init>(Ljava/lang/Object;)V", ordinal = 0))
   private Object lg$translateFull(Object value) {
      return value instanceof String s ? EssentialLang.t(s) : value;
   }
}
