package com.seagull.liquidglass.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.seagull.liquidglass.client.render.GlassSurface;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.FocusableTextWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 26.3 message boxes ({@link FocusableTextWidget} with a background: the generic message screen, out-of-memory screen,
 * …) were a flat black box with a grey 1 px border. The black {@code fill} becomes a refracting glass plate (its alpha
 * byte keeps the widget's fade), and the hard border is dropped while unfocused — the glass rim is the edge. A focused box
 * keeps vanilla's white border so keyboard focus stays visible. Falls back to vanilla if the glass pipeline is unusable.
 */
@Mixin(FocusableTextWidget.class)
public abstract class FocusableTextGlassMixin {

   @WrapOperation(method = "extractWidgetRenderState", at = @At(value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;fill(IIIII)V"))
   private void lg$glassBackground(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int argb, Operation<Void> original) {
      int a = argb >>> 24;
      if (GlassSurface.plate(g, x0, y0, x1, y1, a)) {
         // readability (user glass spec rule 1): a soft grey scrim over the glass so white text holds on bright panels;
         // its radius matches the plate's corner (min half-side * 0.5 * 0.24).
         float r = Math.min(x1 - x0, y1 - y0) / 2.0F * 0.5F * 0.24F;
         GlassSurface.scrim(g, x0, y0, x1, y1, r, (Math.round(0x38 * a / 255.0F) << 24));
      } else {
         original.call(g, x0, y0, x1, y1, argb);
      }
   }

   @WrapOperation(method = "extractWidgetRenderState", at = @At(value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;outline(IIIII)V"))
   private void lg$noHardBorder(GuiGraphicsExtractor g, int x, int y, int w, int h, int argb, Operation<Void> original) {
      if (((AbstractWidget) (Object) this).isFocused()) {
         original.call(g, x, y, w, h, argb);
      }
   }
}
