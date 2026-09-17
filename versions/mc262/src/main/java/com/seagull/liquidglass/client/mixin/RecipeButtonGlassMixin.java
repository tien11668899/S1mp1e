package com.seagull.liquidglass.client.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.seagull.liquidglass.client.render.GlassPipeline;
import com.seagull.liquidglass.client.render.GlassRectRenderState;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.screens.recipebook.RecipeButton;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin({RecipeButton.class})
public abstract class RecipeButtonGlassMixin {
   @Redirect(
      method = {"extractWidgetRenderState"},
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"
      )
   )
   private void lg$glassCell(GuiGraphicsExtractor g, RenderPipeline pipeline, Identifier sprite, int x, int y, int w, int h) {
      if (GlassPipeline.ensureReady() && GlassPipeline.usable()) {
         String p = sprite.getPath();
         boolean lifted = p.contains("craftable") && !p.contains("uncraftable") || p.contains("tab_selected");
         int col = -65536 | (lifted ? '\ud800' : '\uff00') | 0xFF;
         TextureSetup ts = TextureSetup.singleTexture(GlassPipeline.backdropView(), GlassPipeline.sampler());
         ((GuiGraphicsExtractorAccessor)g)
            .liquidglass$guiRenderState()
            .addGuiElement(new GlassRectRenderState(GlassPipeline.glass(), ts, g.pose(), x, y, x + w, y + h, 6, col, null));
      } else {
         g.blitSprite(pipeline, sprite, x, y, w, h);
      }
   }
}
