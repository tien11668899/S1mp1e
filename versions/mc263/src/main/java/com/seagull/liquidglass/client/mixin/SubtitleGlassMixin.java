package com.seagull.liquidglass.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.seagull.liquidglass.client.render.GlassSurface;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.SubtitleOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Subtitles (bottom right, when enabled) sat in square black boxes. Each becomes a rounded dark scrim at vanilla's own
 * (fading) opacity — the soft rounded look of the chat lines — so the arrows and text read exactly as before.
 */
@Mixin(SubtitleOverlay.class)
public abstract class SubtitleGlassMixin {

   @WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;fill(IIIII)V"))
   private void lg$round(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int argb, Operation<Void> original) {
      GlassSurface.scrim(g, x0, y0, x1, y1, Math.min(4.0F, (y1 - y0) / 2.0F), argb);
   }
}
