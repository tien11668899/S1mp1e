package com.seagull.liquidglass.client.compat.rso;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.seagull.liquidglass.client.compat.RsoGlass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Rail page buttons: marker + label colour (white when selected — the glass pill shows the selection). */
@Mixin(targets = "me.flashyreese.mods.reeses_sodium_options.client.gui.frame.tab.TabButtonWidget")
public abstract class RsoTabButtonMixin implements RsoGlass.TabButton {
   @Shadow private boolean selected;

   @Override
   public boolean lg$selected() {
      return this.selected;
   }

   @ModifyExpressionValue(method = "extractRenderState", at = @At(value = "INVOKE",
         target = "Lme/flashyreese/mods/reeses_sodium_options/client/gui/frame/tab/TabButtonWidget;textColor()I"))
   private int lg$textColor(int original) {
      if (!RsoGlass.active) return original;
      return this.selected ? 0xFFFFFFFF : 0xFFC7C7CC;
   }
}
