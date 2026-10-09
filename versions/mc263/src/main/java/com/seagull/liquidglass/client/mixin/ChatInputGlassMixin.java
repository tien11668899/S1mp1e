package com.seagull.liquidglass.client.mixin;

import com.seagull.liquidglass.client.render.ChatCloseFade;
import com.seagull.liquidglass.client.render.GlassPipeline;
import com.seagull.liquidglass.client.render.GlassSurface;
import dev.s1mp1e.client.gui.ScreenOpenFade;
import dev.s1mp1e.client.hud.HudGlass;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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
      // Fade in with the shared screen-open clock (150 ms) instead of popping in when T is pressed. The bar is empty
      // on open, so fading only the glass leaves nothing to pop.
      float fade = ScreenOpenFade.value(Minecraft.getInstance().gui.screen());
      if (GlassPipeline.ensureReady() && GlassPipeline.usable()) {
         if (fade <= 0.004F) return;
         HudGlass.glassBox(g, x0, y0, x1, y1, 0.9F * fade);
         GlassSurface.scrim(g, x0, y0, x1, y1, 0.0F, (Math.round(0x33 * fade) & 0xFF) << 24 | 0x101018);
      } else {
         int a = Math.round((color >>> 24 & 0xFF) * fade) & 0xFF;
         g.fill(x0, y0, x1, y1, a << 24 | color & 0xFFFFFF);
      }
   }

   @org.spongepowered.asm.mixin.Shadow protected net.minecraft.client.gui.components.EditBox input;

   /**
    * Chat closing: hand off to the HUD-level fade-out ghost so the input bar fades instead of popping — together with
    * the text that was visible in the input (e.g. the message just sent), which floats up as it fades.
    */
   @Inject(method = "removed", at = @At("HEAD"))
   private void lg$chatClosing(CallbackInfo ci) {
      String visible = "";
      int x = 4, y = 0;
      try {
         net.minecraft.client.gui.components.EditBox box = this.input;
         if (box != null) {
            EditBoxAccessor acc = (EditBoxAccessor) box;
            String v = box.getValue();
            int dp = Math.max(0, Math.min(acc.liquidglass$displayPos(), v.length()));
            visible = Minecraft.getInstance().font.plainSubstrByWidth(v.substring(dp), box.getInnerWidth());
            x = acc.liquidglass$textX();
            y = acc.liquidglass$textY();
         }
      } catch (Throwable ignored) {}
      ChatCloseFade.begin(visible, x, y);
   }
}
