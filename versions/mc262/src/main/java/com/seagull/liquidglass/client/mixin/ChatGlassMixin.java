package com.seagull.liquidglass.client.mixin;

import com.seagull.liquidglass.client.render.GlassPipeline;
import com.seagull.liquidglass.client.render.GlassSurface;
import dev.s1mp1e.client.hud.HudGlass;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The chat becomes liquid glass: one refracting frosted panel sits behind the visible message column and the vanilla
 * per-line dark rectangles are thinned to a soft readability scrim, so the glass reads through while the text stays
 * legible.
 *
 * <p><b>Geometry &amp; ordering.</b> 26.2 draws the chat in two strata: a BACKGROUND pass ({@code DisplayMode.foreground
 * == false}) that lays the per-line dark rects, and a FOREGROUND pass that draws the text on top. The glass panel is
 * enqueued at the HEAD of the public {@code extractRenderState} on the BACKGROUND pass only, so it lands behind both the
 * scrim rects and the text (26.2 GUI z is pure insertion order). It is placed in the exact scaled chat space vanilla uses
 * — {@code pose.scale(scale); pose.translate(4,0)} then the same {@code x0=-4 .. x1=width+8} local rect vanilla's line
 * backgrounds use — so it lines up with the text at any GUI scale / chat scale.
 *
 * <p><b>Fade.</b> The panel opacity is multiplied by the newest visible line's fade (vanilla's time-based curve when the
 * chat is unfocused, full when the chat is open), so the whole panel fades out together with the messages and never
 * lingers as an empty slab.
 *
 * <p><b>Readability.</b> The per-line dark background fill (drawn inside the {@code forEachLine} line lambda) is redirected
 * to a thinner alpha so the frosted glass shows through while still giving text a scrim. When the glass pipeline is not
 * usable the panel is skipped and the vanilla rects are left at full strength, so chat never renders worse than vanilla.
 */
@Mixin(ChatComponent.class)
public abstract class ChatGlassMixin {

   @Shadow @Final private List<GuiMessage.Line> trimmedMessages;
   @Shadow private int chatScrollbarPos;

   @Shadow private double getScale() { return 0; }
   @Shadow private int getWidth() { return 0; }
   @Shadow private int getLineHeight() { return 0; }
   @Shadow public abstract int getLinesPerPage();

   /** Bottom-of-screen margin the chat is anchored above (vanilla literal in the private extract). */
   private static final int CHAT_BOTTOM_MARGIN = 40;
   /** Base frosted-panel opacity before the message-fade multiplier. */
   private static final float PANEL_ALPHA = 0.82F;
   /** Grey readability scrim laid over the frosted panel, before the text (LOOK SPEC: scrim under text). */
   private static final int PANEL_SCRIM = 0x66101018;
   /** Right padding past the widest visible line, so the panel hugs the text instead of the full chat column. */
   private static final int TEXT_RIGHT_PAD = 6;

   @Inject(
      method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Font;IIILnet/minecraft/client/gui/components/ChatComponent$DisplayMode;Z)V",
      at = @At("HEAD")
   )
   private void lg$chatPanel(GuiGraphicsExtractor g, Font font, int tickCount, int mouseX, int mouseY,
                             ChatComponent.DisplayMode displayMode, boolean focused, CallbackInfo ci) {
      if (displayMode.foreground) return;                       // background stratum only
      if (!(GlassPipeline.ensureReady() && GlassPipeline.usable())) return;

      int size = this.trimmedMessages.size();
      int shown = size - this.chatScrollbarPos;
      if (shown <= 0 || this.chatScrollbarPos < 0 || this.chatScrollbarPos >= size) return;
      int n = Math.min(this.getLinesPerPage(), shown);
      if (n <= 0) return;

      // Fade the whole panel with the newest visible line (full when chat is open).
      float fade = focused ? 1.0F : lg$lineFade(tickCount - this.trimmedMessages.get(this.chatScrollbarPos).addedTime());
      if (fade <= 0.02F) return;

      double scale = this.getScale();
      if (!(scale > 0.0)) return;
      int lineHeight = this.getLineHeight();
      int bottomY = Mth.floor((g.guiHeight() - CHAT_BOTTOM_MARGIN) / (float) scale);
      int y0 = bottomY - n * lineHeight;
      int y1 = bottomY;

      // Hug the actual text: width = widest visible line (not the full chat column, which would leave empty glass).
      int textW = 0;
      for (int k = this.chatScrollbarPos; k < this.chatScrollbarPos + n && k < size; k++) {
         textW = Math.max(textW, font.width(this.trimmedMessages.get(k).content()));
      }
      if (textW <= 0) return;
      int x0 = -4;
      int x1 = textW + TEXT_RIGHT_PAD;

      g.pose().pushMatrix();
      g.pose().scale((float) scale, (float) scale);
      g.pose().translate(4.0F, 0.0F);
      HudGlass.glassBox(g, x0, y0, x1, y1, PANEL_ALPHA * fade);
      int sa = Math.round((PANEL_SCRIM >>> 24 & 0xFF) * fade) & 0xFF;   // scrim tracks the same fade
      GlassSurface.scrim(g, x0, y0, x1, y1, 0.0F, sa << 24 | PANEL_SCRIM & 0xFFFFFF);
      g.pose().popMatrix();
   }

   /** Vanilla's unfocused per-line fade curve: fully visible for ~180 ticks, then a quick quadratic fade out by 200. */
   private static float lg$lineFade(int ticksLived) {
      double v = 1.0 - ticksLived / 200.0;
      v = Mth.clamp(v * 10.0, 0.0, 1.0);
      return (float) (v * v);
   }

   /**
    * Drop the vanilla full-chat-width per-line dark rectangle when the glass is up — the snug frosted panel + grey scrim
    * enqueued at HEAD already backs the text, and keeping the full-width rects would paint a dark bar out to the right of
    * the panel. When the glass pipeline is not usable the vanilla rect is drawn unchanged, so chat is never worse than
    * vanilla.
    */
   @Redirect(
      method = "lambda$extractRenderState$1",
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/gui/components/ChatComponent$ChatGraphicsAccess;fill(IIIII)V"
      )
   )
   private static void lg$dropLineBg(ChatComponent.ChatGraphicsAccess access, int x0, int y0, int x1, int y1, int color) {
      if (!(GlassPipeline.ensureReady() && GlassPipeline.usable())) {
         access.fill(x0, y0, x1, y1, color);
      }
   }
}
