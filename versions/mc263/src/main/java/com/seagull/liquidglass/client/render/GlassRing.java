package com.seagull.liquidglass.client.render;

import com.seagull.liquidglass.client.mixin.GuiGraphicsExtractorAccessor;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.TextureSetup;

/**
 * Round glass pieces for combat HUD: a liquid-glass RING band (same material as every other glass surface, only the
 * shape differs - glass_ring.fsh) and a flat ARC with round caps on top of it (ring_arc.fsh). Shapes: 0 circle,
 * 1 rounded square, 2 plus that wraps the crosshair (arm half-width / reach = {@code ratio}). Centre and sizes are GUI
 * px under the extractor's current pose. Both return false when their pipeline is unusable so callers can fall back.
 */
public final class GlassRing {
   private GlassRing() {
   }

   /** Frost 0.5 - the panel material; the corner byte is the band thickness and the lift byte the shape here. */
   private static final int FROST = 0x80;
   private static final int PAD = 10;

   /** Plus arm ratio -> 0..15 (ratio = 0.08 + code / 15 * 0.84), shared with the shaders. */
   public static int ratioCode(float ratio) {
      return Math.max(0, Math.min(15, Math.round((ratio - 0.08F) / 0.84F * 15.0F)));
   }

   /** Plus central-hole half-size / reach -> 0..14 (gap = code / 14 * 0.92). 0 = the joined plus. */
   public static int gapCode(float gap) {
      return Math.max(0, Math.min(14, Math.round(gap / 0.92F * 14.0F)));
   }

   /** glass_ring.fsh G byte: 255 circle, 245 rounded square, else plus = gapCode*16 + ratioCode (0..239). */
   private static int shapeByte(int shape, float ratio, float gap) {
      return shape == 2 ? (gapCode(gap) * 16 + ratioCode(ratio)) : (shape == 1 ? 0xF5 : 0xFF);
   }

   public static boolean glass(GuiGraphicsExtractor g, float cx, float cy, float outerR, float thickness, float opacity) {
      return glass(g, cx, cy, outerR, thickness, opacity, 0, 0.45F, 0.0F);
   }

   public static boolean glass(GuiGraphicsExtractor g, float cx, float cy, float outerR, float thickness, float opacity,
                               int shape, float ratio) {
      return glass(g, cx, cy, outerR, thickness, opacity, shape, ratio, 0.0F);
   }

   /** Glass band of outer size {@code outerR} and width {@code thickness} on {@code shape}, at {@code opacity} (0..1). */
   public static boolean glass(GuiGraphicsExtractor g, float cx, float cy, float outerR, float thickness, float opacity,
                               int shape, float ratio, float gap) {
      if (!(GlassPipeline.ensureReady() && GlassPipeline.ringGlassUsable()) || outerR <= 0.0F) {
         return false;
      }
      int op = Math.round(Math.max(0.0F, Math.min(1.0F, opacity)) * 255.0F) & 0xFF;
      if (op == 0) {
         return true;
      }
      int thick = Math.round(Math.max(0.02F, Math.min(1.0F, thickness / outerR)) * 255.0F) & 0xFF;
      int color = (FROST << 24) | (thick << 16) | (shapeByte(shape, ratio, gap) << 8) | op;
      int x0 = Math.round(cx - outerR);
      int y0 = Math.round(cy - outerR);
      int size = Math.round(outerR * 2.0F);
      TextureSetup ts = TextureSetup.singleTexture(GlassPipeline.backdropView(), GlassPipeline.sampler());
      ((GuiGraphicsExtractorAccessor)g).liquidglass$guiRenderState()
         .addGuiElement(new GlassRectRenderState(GlassPipeline.ringGlass(), ts, g.pose(), x0, y0, x0 + size, y0 + size, PAD, color, null));
      return true;
   }

   public static boolean arc(GuiGraphicsExtractor g, float cx, float cy, float outerR, float thickness, float progress, int argb) {
      return arc(g, cx, cy, outerR, thickness, progress, argb, 0, 0.45F, 0.0F);
   }

   public static boolean arc(GuiGraphicsExtractor g, float cx, float cy, float outerR, float thickness, float progress, int argb,
                             int shape, float ratio) {
      return arc(g, cx, cy, outerR, thickness, progress, argb, shape, ratio, 0.0F);
   }

   /** Flat arc from 12 o'clock over {@code |progress|} of {@code shape} (clockwise if positive), colour {@code argb}. */
   public static boolean arc(GuiGraphicsExtractor g, float cx, float cy, float outerR, float thickness, float progress, int argb,
                             int shape, float ratio, float gap) {
      if (!(GlassPipeline.ensureReady() && GlassPipeline.ringArcUsable()) || outerR <= 0.0F || progress == 0.0F || (argb >>> 24) == 0) {
         return GlassPipeline.ringArcUsable();
      }
      ((GuiGraphicsExtractorAccessor)g).liquidglass$guiRenderState()
         .addGuiElement(new RingArcRenderState(GlassPipeline.ringArc(), g.pose(), cx, cy, outerR, thickness, progress, shape, ratio, gap, argb, null));
      return true;
   }
}
