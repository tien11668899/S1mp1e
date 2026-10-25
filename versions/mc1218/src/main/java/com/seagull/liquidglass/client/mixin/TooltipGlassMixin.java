package com.seagull.liquidglass.client.mixin;

import com.seagull.liquidglass.client.render.TooltipGlass;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.TooltipRenderUtil;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({TooltipRenderUtil.class})
public abstract class TooltipGlassMixin {
   @Inject(
      method = {"renderTooltipBackground"},
      at = {@At("HEAD")},
      cancellable = true
   )
   private static void lg$glassTooltip(GuiGraphics g, int x, int y, int w, int h, @Nullable ResourceLocation style, CallbackInfo ci) {
      int x0 = x - 3;
      int y0 = y - 3;
      int pw = w + 6;
      int ph = h + 6;
      if (TooltipGlass.draw(g, x0, y0, pw, ph)) {
         ci.cancel();
      }
   }
}
