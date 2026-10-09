package com.seagull.liquidglass.client.mixin;

import com.seagull.liquidglass.client.render.ChatCloseFade;
import com.seagull.liquidglass.client.render.GlassPipeline;
import com.seagull.liquidglass.client.render.GlassSurface;
import dev.s1mp1e.client.hud.HudGlass;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draw the chat input bar fading out after chat closes. {@code ChatScreen} owns the bar and is gone the instant chat
 * closes, so {@link ChatCloseFade} (armed from {@code ChatScreen.removed()}) lets the HUD paint a ghost of the same frosted
 * glass bar at the same bottom rectangle for ~150 ms, its alpha falling to 0. Hooked at the tail of {@code Hud.extractChat}
 * so it sits with the rest of the chat area. Skipped while a chat screen is open (the screen draws the real bar) and when
 * the glass pipeline is down.
 */
@Mixin(Hud.class)
public class ChatCloseGhostMixin {

   @Inject(method = "extractChat", at = @At("TAIL"))
   private void lg$chatCloseGhost(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
      if (!ChatCloseFade.active()) return;
      Minecraft mc = Minecraft.getInstance();
      if (mc.gui.screen() instanceof ChatScreen) return;            // reopened: the screen draws the real bar
      float a = ChatCloseFade.alpha();
      int w = mc.getWindow().getGuiScaledWidth();
      int h = mc.getWindow().getGuiScaledHeight();
      if (a > 0.004F && GlassPipeline.ensureReady() && GlassPipeline.usable()) {
         int x0 = 2, y0 = h - 14, x1 = w - 2, y1 = h - 2;              // vanilla chat input rectangle
         HudGlass.glassBox(g, x0, y0, x1, y1, 0.9F * a);
         GlassSurface.scrim(g, x0, y0, x1, y1, 0.0F, (Math.round(0x33 * a) & 0xFF) << 24 | 0x101018);
      }
      // the text that was in the input lifts off: floats up 5 px (ease-out) while fading (ease-in)
      String text = ChatCloseFade.text();
      float q = ChatCloseFade.textProgress();
      if (!text.isEmpty() && q < 1F) {
         float lift = 5.0F * (1F - (1F - q) * (1F - q));
         int alpha = Math.round(255F * (1F - q) * (1F - q)) & 0xFF;
         if (alpha >= 4) {
            org.joml.Matrix3x2fStack pose = g.pose();
            pose.pushMatrix();
            pose.translate(0.0F, -lift);
            try {
               g.text(mc.font, text, ChatCloseFade.textX(), ChatCloseFade.textY(), alpha << 24 | 0xE0E0E0, true);
            } finally {
               pose.popMatrix();
            }
         }
      }
   }
}
