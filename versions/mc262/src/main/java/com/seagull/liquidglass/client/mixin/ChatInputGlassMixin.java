package com.seagull.liquidglass.client.mixin;

import com.seagull.liquidglass.client.render.GlassPipeline;
import com.seagull.liquidglass.client.render.GlassSurface;
import dev.s1mp1e.client.hud.HudGlass;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The open chat input field becomes a liquid-glass bar. {@code ChatScreen.extractRenderState} draws its input box as a
 * single {@code g.fill(2, height-14, width-2, height-2, backgroundColor)}; that fill is redirected to a frosted glass bar
 * (refracting panel + a faint grey scrim so the typed text and the command-suggestion box stay readable) at the same
 * rectangle, inheriting the screen's pose. Falls back to the vanilla fill when the glass pipeline is not usable.
 */
@Mixin(ChatScreen.class)
public abstract class ChatInputGlassMixin {

   @Redirect(
      method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;fill(IIIII)V"
      )
   )
   private void lg$inputBar(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color) {
      if (GlassPipeline.ensureReady() && GlassPipeline.usable()) {
         HudGlass.glassBox(g, x0, y0, x1, y1, 0.9F);
         GlassSurface.scrim(g, x0, y0, x1, y1, 0.0F, 0x33101018);
      } else {
         g.fill(x0, y0, x1, y1, color);
      }
   }
}
