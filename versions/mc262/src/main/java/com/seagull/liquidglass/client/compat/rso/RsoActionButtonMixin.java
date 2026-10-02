package com.seagull.liquidglass.client.compat.rso;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.seagull.liquidglass.client.compat.RsoGlass;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The per-option reset / undo buttons: RSO's 40 % black square + orange icon → a small glass circle with the
 * matching SF Symbol (↺ reset, ↶ undo) in white.
 */
@Mixin(targets = "me.flashyreese.mods.reeses_sodium_options.client.gui.frame.option.action.OptionActionButtonRenderer")
public abstract class RsoActionButtonMixin {
   @Redirect(method = "render", at = @At(value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;fill(IIIII)V"))
   private static void lg$background(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int color) {
      if (RsoGlass.active) RsoGlass.actionBackground(g, x1, y1, x2, y2, color);
      else g.fill(x1, y1, x2, y2, color);
   }

   @Redirect(method = "render", at = @At(value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blit(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIIII)V"))
   private static void lg$icon(GuiGraphicsExtractor g, RenderPipeline pipeline, Identifier tex, int x, int y, float u, float v,
                               int w, int h, int texW, int texH, int color) {
      if (RsoGlass.active && RsoGlass.actionIcon(g, tex.getPath(), x, y, w, h, color)) return;
      g.blit(pipeline, tex, x, y, u, v, w, h, texW, texH, color);
   }
}
