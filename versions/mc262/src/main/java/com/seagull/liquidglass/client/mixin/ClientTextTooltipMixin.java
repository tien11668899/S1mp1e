package com.seagull.liquidglass.client.mixin;

import com.seagull.liquidglass.client.render.TooltipGlass;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTextTooltip;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin({ClientTextTooltip.class})
public abstract class ClientTextTooltipMixin {
   @Redirect(
      method = {"extractText"},
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)V"
      )
   )
   private void lg$fadeText(GuiGraphicsExtractor g, Font font, FormattedCharSequence text, int x, int y, int color, boolean shadow) {
      int a = TooltipGlass.textAlphaByte();
      if (a >= 252) {
         g.text(font, text, x, y, color, shadow);
      } else if (a >= 8) {
         int rgb = color & 16777215;
         g.text(font, text, x, y, a << 24 | rgb, shadow);
      }
   }
}
