package com.seagull.liquidglass.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.seagull.liquidglass.client.render.GlassSurface;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.reporting.ReportReasonSelectionScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The report-reason screen's description box was a black rect with a white 1 px outline. It becomes the frosted field
 * every text box uses (no hard outline); the description text draws on top unchanged.
 */
@Mixin(ReportReasonSelectionScreen.class)
public abstract class ReportDescriptionGlassMixin {

   @WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;fill(IIIII)V"))
   private void lg$glassBox(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int argb, Operation<Void> original) {
      GlassSurface.scrim(g, x0, y0, x1, y1, 4.0F, 0x2EFFFFFF);
   }

   @WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;outline(IIIII)V"))
   private void lg$noOutline(GuiGraphicsExtractor g, int x, int y, int w, int h, int argb, Operation<Void> original) {
   }
}
