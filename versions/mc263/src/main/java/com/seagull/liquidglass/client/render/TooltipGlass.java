package com.seagull.liquidglass.client.render;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.seagull.liquidglass.client.animation.Fade;
import com.seagull.liquidglass.client.animation.Spring;
import com.seagull.liquidglass.client.mixin.GuiGraphicsExtractorAccessor;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.TextureSetup;

public final class TooltipGlass {
   private static Spring sx;
   private static Spring sy;
   private static Spring sw;
   private static Spring sh;
   private static final Fade panelFade = new Fade(0.0F, 150.0F);
   private static final Fade textFade = new Fade(1.0F, 150.0F);
   private static boolean activeThisFrame = false;
   /** Grey readability scrim under the tooltip text (over the glass): colour + peak alpha. */
   private static final int SCRIM_RGB = 0x16161A;
   private static final int SCRIM_ALPHA = 0x48;   // ~28 % (was 0x70 ~44 %; user: "灰底不透明低一點")
   /** Card quads added since the last GuiRenderer.render — dev layer probe only, filled only while it is armed. */
   private static final ArrayList<GlassRectRenderState> frameQuads = new ArrayList<>();

   private TooltipGlass() {
   }

   public static boolean draw(GuiGraphicsExtractor g, int x0, int y0, int w, int h) {
      if (GlassPipeline.ensureReady() && GlassPipeline.usable()) {
         if (panelFade.isVisible() && sx != null) {
            if (Math.abs(sw.target() - (float)w) > 2.0F
               || Math.abs(sh.target() - (float)h) > 2.0F
               || Math.abs(sx.target() - (float)x0) > 6.0F
               || Math.abs(sy.target() - (float)y0) > 6.0F) {
               textFade.snap(0.0F);
            }

            sx.setTarget((float)x0);
            sy.setTarget((float)y0);
            sw.setTarget((float)w);
            sh.setTarget((float)h);
         } else {
            sx = new Spring((float)x0, 27.0F, 1.0F);
            sy = new Spring((float)y0, 27.0F, 1.0F);
            sw = new Spring((float)w, 27.0F, 1.0F);
            sh = new Spring((float)h, 27.0F, 1.0F);
            textFade.snap(0.0F);
         }

         textFade.to(1.0F);
         float rem = 0.016666668F;

         while (rem > 0.0F) {
            float hh = Math.min(rem, 0.008333334F);
            sx.update(hh);
            sy.update(hh);
            sw.update(hh);
            sh.update(hh);
            rem -= hh;
         }

         if (!Float.isFinite(sx.value()) || !Float.isFinite(sy.value()) || !Float.isFinite(sw.value()) || !Float.isFinite(sh.value())) {
            sx.snap((float)x0);
            sy.snap((float)y0);
            sw.snap((float)w);
            sh.snap((float)h);
         }

         panelFade.to(1.0F);
         activeThisFrame = true;
         drawQuad(g);
         return true;
      } else {
         return false;
      }
   }

   /**
    * Once per screen frame, AFTER the screen's deferred tooltip ran ({@code GuiGraphicsExtractor.extractDeferredElements}
    * RETURN, see {@code TooltipLayerMixin}): if no tooltip card drew this frame, fade the card out at its last pose. The
    * ghost gets its own stratum through {@link TooltipLayer}, so it is on the same TOP layer as a live card.
    * (Previously called from {@code ContainerCloseGhostMixin} at {@code extractSlotHighlightBack} TAIL — i.e. in the
    * screen's content stratum, UNDER the slot items — and in practice never: that method is cancelled at HEAD whenever
    * glass is up, so its TAIL injector never ran and the card never faded.)
    */
   public static void ghostPass(GuiGraphicsExtractor g) {
      if (activeThisFrame) {
         activeThisFrame = false;
      } else if (panelFade.isVisible() && sx != null && GlassPipeline.usable()) {
         panelFade.to(0.0F);
         if (panelFade.isVisible()) {
            TooltipLayer.begin(g);
            drawQuad(g);
            TooltipLayer.end(g);
         }
      } else {
         panelFade.snap(0.0F);
      }
   }

   public static int textAlphaByte() {
      return GlassPipeline.usable() && sx != null ? Math.round(Math.min(panelFade.value(), textFade.value()) * 255.0F) & 0xFF : 255;
   }

   private static void drawQuad(GuiGraphicsExtractor g) {
      int x = Math.round(sx.value());
      int y = Math.round(sy.value());
      int w = Math.round(sw.value());
      int h = Math.round(sh.value());
      int ab = Math.round(panelFade.value() * 255.0F) & 0xFF;
      int col = -2132017408 | ab;
      // The card is the TOP layer, so it refracts the GUI beneath it (panel, slots, items), not the world-only backdrop the
      // panels use: TooltipLayer splits the GUI draw right before this quad and copies the framebuffer into overlayView.
      GpuTextureView under = GlassPipeline.overlayView();
      TextureSetup ts = TextureSetup.singleTexture(under != null ? under : GlassPipeline.backdropView(), GlassPipeline.sampler());
      GlassRectRenderState card = new GlassRectRenderState(GlassPipeline.glass(), ts, g.pose(), x, y, x + w, y + h, 8, col, null);
      if (under != null) {
         TooltipLayer.addCard(card);
      }
      ((GuiGraphicsExtractorAccessor)g).liquidglass$guiRenderState().addGuiElement(card);
      if (under != null) {
         // Readability underlay (liquid-glass rule 2: busy content under the glass -> a slight grey scrim): the card now
         // shows the slot items it covers, so the tooltip text gets a soft dark-grey layer between the glass and the text.
         // Inset 1 px so the glass rim / edge refraction stays visible; same corner as the glass (min(w,h) * .23).
         // Added after the card and intersecting it, so it stacks above the card and below the text (node order).
         int sa = Math.round(SCRIM_ALPHA * panelFade.value()) & 0xFF;
         if (sa > 0 && w > 2 && h > 2) {
            float r = Math.max(0.0F, Math.min(w, h) * 0.23F - 1.0F);
            GlassSurface.scrim(g, x + 1, y + 1, x + w - 1, y + h - 1, r, sa << 24 | SCRIM_RGB);
         }
      }
      if (dev.s1mp1e.client.GuiLayerProbe.armed && frameQuads.size() < 8) {
         frameQuads.add(card);
      }
   }

   public static List<GlassRectRenderState> frameQuads() {
      return frameQuads;
   }

   public static void clearFrameQuads() {
      frameQuads.clear();
   }
}
