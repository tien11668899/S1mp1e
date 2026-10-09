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
 * becomes a rounded frosted scrim instead (focused: brighter). Text, hint and caret are untouched (and keep the bordered
 * inset). Applies to every bordered text field, matching the multi-line text areas ({@code TextAreaGlassMixin}).
 */
@Mixin(EditBox.class)
public abstract class EditBoxFrameGlassMixin {
   @Redirect(
      method = "extractWidgetRenderState",
      at = @At(value = "INVOKE",
               target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V")
   )
   private void lg$glassFrame(GuiGraphicsExtractor g, RenderPipeline pipeline, Identifier sprite, int x, int y, int w, int h) {
      // Every bordered EditBox is glass (all-UI audit: direct connect / server edit / world name / command block / key
      // search were still vanilla black). Borderless boxes (anvil rename, chat input) never reach this sprite call.
      if (true) {
         boolean focused = ((EditBox) (Object) this).isFocused();
         GlassSurface.scrim(g, x, y, x + w, y + h, 4.0F, focused ? 0x4DFFFFFF : 0x2EFFFFFF);
      } else {
         g.blitSprite(pipeline, sprite, x, y, w, h);
      }
   }
}
