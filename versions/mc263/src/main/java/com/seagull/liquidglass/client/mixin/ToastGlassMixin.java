package com.seagull.liquidglass.client.mixin;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.seagull.liquidglass.client.render.GlassPipeline;
import com.seagull.liquidglass.client.render.GlassSurface;
import dev.s1mp1e.client.hud.HudGlass;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.AdvancementToast;
import net.minecraft.client.gui.components.toasts.RecipeToast;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.components.toasts.TutorialToast;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Advancement / recipe / system / tutorial toasts become liquid-glass cards. Each concrete toast draws its frame as the
 * first {@code g.blitSprite(pipeline, BACKGROUND_SPRITE, 0, 0, width, height)} of its {@code extractRenderState}; that blit
 * (ordinal 0) is redirected to a frosted glass card plus a faint grey scrim for text readability. Because the redirect sits
 * at the blit call site — after the {@link net.minecraft.client.gui.components.toasts.ToastManager} has applied the toast's
 * slide-in pose translation — the card inherits the slide, so it moves with the toast. The toast's own icon and text draw
 * on top unchanged. Falls back to the vanilla sprite when the glass pipeline is not usable.
 */
@Mixin({AdvancementToast.class, RecipeToast.class, SystemToast.class, TutorialToast.class})
public abstract class ToastGlassMixin {

   @Redirect(
      method = "extractRenderState",
      at = @At(
         value = "INVOKE",
         ordinal = 0,
         target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"
      )
   )
   private void lg$toastCard(GuiGraphicsExtractor g, RenderPipeline pipeline, Identifier sprite, int x, int y, int w, int h) {
      if (GlassPipeline.ensureReady() && GlassPipeline.usable()) {
         HudGlass.glassBox(g, x, y, x + w, y + h, 0.92F);
         GlassSurface.scrim(g, x, y, x + w, y + h, 6.0F, 0x30101018);
      } else {
         g.blitSprite(pipeline, sprite, x, y, w, h);
      }
   }
}
