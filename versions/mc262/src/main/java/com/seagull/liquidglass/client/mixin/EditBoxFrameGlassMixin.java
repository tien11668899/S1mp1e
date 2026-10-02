package com.seagull.liquidglass.client.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.seagull.liquidglass.client.render.EditBoxGlass;
import com.seagull.liquidglass.client.render.GlassSurface;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * A bordered {@link EditBox} draws its frame as one opaque sprite (black box, grey outline). While
 * {@link EditBoxGlass#frame} is set — only around the recipe book's search field, see {@code RecipeBookGlassMixin} — that sprite
 * becomes a rounded frosted scrim instead, so the field reads as part of the glass book. Text, hint and caret are
 * untouched (and keep the bordered inset). Every other text field keeps its vanilla frame.
 */
@Mixin(EditBox.class)
public abstract class EditBoxFrameGlassMixin {
   @Redirect(
      method = "extractWidgetRenderState",
      at = @At(value = "INVOKE",
               target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V")
   )
   private void lg$glassFrame(GuiGraphicsExtractor g, RenderPipeline pipeline, Identifier sprite, int x, int y, int w, int h) {
      if (EditBoxGlass.frame || net.minecraft.client.Minecraft.getInstance().gui.screen()
            instanceof net.minecraft.client.gui.screens.worldselection.SelectWorldScreen) {   // + world list search
         boolean focused = ((EditBox) (Object) this).isFocused();
         GlassSurface.scrim(g, x, y, x + w, y + h, 4.0F, focused ? 0x4DFFFFFF : 0x2EFFFFFF);
      } else {
         g.blitSprite(pipeline, sprite, x, y, w, h);
      }
   }
}
