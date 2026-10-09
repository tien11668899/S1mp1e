package com.seagull.liquidglass.client.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.seagull.liquidglass.client.render.GlassSurface;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractTextAreaWidget;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Multi-line text areas (book-and-quill style multi-line edit boxes, the telemetry event list, …) framed themselves with
 * vanilla's text-field sprite: a solid black box with a 1 px white border. That sprite becomes the same rounded frosted
 * scrim as the single-line text fields ({@code EditBoxFrameGlassMixin}) — brighter while focused — so the two read as one
 * family. Text, caret and the overlay scroller draw on top unchanged.
 */
@Mixin(AbstractTextAreaWidget.class)
public abstract class TextAreaGlassMixin {

   @Redirect(
      method = "extractBorder",
      at = @At(value = "INVOKE",
               target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V")
   )
   private void lg$glassArea(GuiGraphicsExtractor g, RenderPipeline pipeline, Identifier sprite, int x, int y, int w, int h) {
      boolean focused = ((AbstractTextAreaWidget) (Object) this).isFocused();
      GlassSurface.scrim(g, x, y, x + w, y + h, 4.0F, focused ? 0x4DFFFFFF : 0x2EFFFFFF);
   }
}
