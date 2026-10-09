package com.seagull.liquidglass.client.mixin;

import com.seagull.liquidglass.client.render.TooltipGlass;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.TooltipRenderUtil;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({TooltipRenderUtil.class})
public abstract class TooltipGlassMixin {
   @Inject(
      method = {"extractTooltipBackground"},
      at = {@At("HEAD")},
      cancellable = true
   )
   private static void lg$glassTooltip(GuiGraphicsExtractor g, int x, int y, int w, int h, @Nullable Identifier style, CallbackInfo ci) {
      int x0 = x - 3;
      int y0 = y - 3;
      int pw = w + 6;
      int ph = h + 6;
      if (TooltipGlass.draw(g, x0, y0, pw, ph)) {
         ci.cancel();
      }
   }
}
