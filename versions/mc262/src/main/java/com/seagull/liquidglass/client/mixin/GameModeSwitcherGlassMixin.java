package com.seagull.liquidglass.client.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.seagull.liquidglass.client.render.GlassSurface;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.debug.GameModeSwitcherScreen;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * F3+F4's game-mode switcher sat on vanilla's dark grey {@code gamemode_switcher.png} panel. The panel becomes a glass plate
 * with a grey scrim (readable white mode name); the slots and selection are glass tiles via {@code SpritePathGlassMixin}.
 */
@Mixin(GameModeSwitcherScreen.class)
public abstract class GameModeSwitcherGlassMixin {

   @Redirect(method = "extractBackground", at = @At(value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blit(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIII)V"))
   private void lg$glassPanel(GuiGraphicsExtractor g, RenderPipeline pipeline, Identifier tex, int x, int y, float u, float v,
                              int w, int h, int tw, int th) {
      if (GlassSurface.plate(g, x, y, x + w, y + h)) {
         GlassSurface.scrim(g, x, y, x + w, y + h, Math.min(w, h) / 2.0F * 0.5F * 0.24F, 0x30000000);
      } else {
         g.blit(pipeline, tex, x, y, u, v, w, h, tw, th);
      }
   }
}
