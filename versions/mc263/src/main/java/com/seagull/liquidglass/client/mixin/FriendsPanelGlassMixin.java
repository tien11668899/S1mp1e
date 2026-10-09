package com.seagull.liquidglass.client.mixin;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.seagull.liquidglass.client.render.GlassSurface;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 26.2's friends overlay and its confirm dialog sat on vanilla's opaque dark-grey nine-slice panel with a grey rim. The
 * panel sprite becomes a refracting glass plate (the container panels' recipe) with a soft grey scrim on top so the
 * white names and status text stay readable (user glass spec rule 1). Falls back to the sprite if glass is unusable.
 */
@Mixin(targets = {
      "net.minecraft.client.gui.screens.friends.FriendsOverlayScreen",
      "net.minecraft.client.gui.screens.friends.FriendsListConfirmScreen"
})
public abstract class FriendsPanelGlassMixin {

   @Redirect(
      method = "extractBackground",
      at = @At(value = "INVOKE",
               target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V")
   )
   private void lg$glassPanel(GuiGraphicsExtractor g, RenderPipeline pipeline, Identifier sprite, int x, int y, int w, int h) {
      if (GlassSurface.plate(g, x, y, x + w, y + h)) {
         GlassSurface.scrim(g, x, y, x + w, y + h, Math.min(w, h) / 2.0F * 0.5F * 0.24F, 0x30000000);
      } else {
         g.blitSprite(pipeline, sprite, x, y, w, h);
      }
   }
}
