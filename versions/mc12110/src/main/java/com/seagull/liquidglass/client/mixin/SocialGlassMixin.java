package com.seagull.liquidglass.client.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.seagull.liquidglass.client.render.GlassPipeline;
import com.seagull.liquidglass.client.render.GlassSurface;
import dev.s1mp1e.client.gui.ScreenOpenFade;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.social.SocialInteractionsScreen;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The social-interactions panel becomes a liquid-glass plate: its nine-slice {@code BACKGROUND_SPRITE} is redirected to a
 * refracting panel, while the small {@code SEARCH_SPRITE} icon (drawn by the same {@code blitSprite(...IIII)} shape) is left
 * vanilla — distinguished by width, since the background is the wide sprite and the search icon is small.
 */
@Mixin(SocialInteractionsScreen.class)
public abstract class SocialGlassMixin {

   @Redirect(
      method = "renderBackground",
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphics;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/ResourceLocation;IIII)V"
      )
   )
   private void lg$socialBg(GuiGraphics g, RenderPipeline pipeline, ResourceLocation sprite, int x, int y, int w, int h) {
      // The big panel sprite is ~236 wide; the search icon is small. Only glass the panel.
      if (w >= 120 && GlassPipeline.ensureReady() && GlassPipeline.usable()) {
         int opacity = Math.round(0xFF * ScreenOpenFade.value(Minecraft.getInstance().screen)) & 0xFF;
         if (GlassSurface.plate(g, x, y, x + w, y + h, opacity)) {
            return;
         }
      }
      g.blitSprite(pipeline, sprite, x, y, w, h);
   }
}
