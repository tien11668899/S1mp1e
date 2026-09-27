package com.seagull.liquidglass.client.mixin;

import com.seagull.liquidglass.client.render.GlassPipeline;
import dev.s1mp1e.client.hud.HudGlass;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The action-bar / item-pickup message becomes a liquid-glass pill, matching the hotbar's selected-item-name pill.
 * {@code Hud.extractOverlayMessage} draws the text with {@code g.textWithBackdrop(font, text, x, y, maxX, argb)} inside its
 * own overlay stratum, under a pose translated to (guiWidth/2, guiHeight-68); the backdrop is a plain dark rectangle. That
 * call is redirected to a frosted glass pill sized to the text, then the text is drawn on top with a shadow. The pill
 * opacity tracks the {@code argb} alpha, which already carries the message's fade-out timer, so the pill fades out with the
 * text. Falls back to the vanilla {@code textWithBackdrop} when the glass pipeline is not usable.
 */
@Mixin(Hud.class)
public abstract class ActionBarGlassMixin {

   @Redirect(
      method = "extractOverlayMessage",
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;textWithBackdrop(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIII)V"
      )
   )
   private void lg$actionBarPill(GuiGraphicsExtractor g, Font font, Component text, int x, int y, int maxX, int argb) {
      if (GlassPipeline.ensureReady() && GlassPipeline.usable()) {
         float af = (argb >>> 24 & 0xFF) / 255.0F;
         HudGlass.glassBox(g, x - 5, y - 2, x + maxX + 5, y + 11, 0.85F * af);
         g.text(font, text, x, y, argb, true);
      } else {
         g.textWithBackdrop(font, text, x, y, maxX, argb);
      }
   }
}
