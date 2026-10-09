package com.seagull.liquidglass.client.render;

import net.minecraft.client.gui.GuiGraphicsExtractor;

public final class GlassPainter {
   private GlassPainter() {
   }

   public static int argb(float a, int r, int g, int b) {
      int ai = clamp255((int)(a * 255.0F + 0.5F));
      return ai << 24 | clamp255(r) << 16 | clamp255(g) << 8 | clamp255(b);
   }

   private static int clamp255(int v) {
      return v < 0 ? 0 : (v > 255 ? 255 : v);
   }

   private static int lerpChannel(int a, int b, float t, int shift) {
      int ca = a >>> shift & 0xFF;
      int cb = b >>> shift & 0xFF;
      return (int)((float)ca + (float)(cb - ca) * t + 0.5F) & 0xFF;
   }

   public static int lerp(int a, int b, float t) {
      return lerpChannel(a, b, t, 24) << 24 | lerpChannel(a, b, t, 16) << 16 | lerpChannel(a, b, t, 8) << 8 | lerpChannel(a, b, t, 0);
   }

   private static float capInset(float dyc, float r) {
      float d = r * r - dyc * dyc;
      return d <= 0.0F ? r : r - (float)Math.sqrt((double)d);
   }

   public static void capsule(GuiGraphicsExtractor g, float x, float y, float w, float h, float radius, int colTop, int colBot) {
      int ih = Math.round(h);
      if (ih > 0 && !(w <= 0.0F)) {
         float r = Math.min(radius, h * 0.5F);
         float cy = h * 0.5F;

         for (int row = 0; row < ih; row++) {
            float dyc = (float)row + 0.5F - cy;
            float inset = capInset(dyc, r);
            int x1 = Math.round(x + inset);
            int x2 = Math.round(x + w - inset);
            if (x2 > x1) {
               float t = ih <= 1 ? 0.0F : (float)row / (float)(ih - 1);
               int col = lerp(colTop, colBot, t);
               int yy = Math.round(y) + row;
               g.fill(x1, yy, x2, yy + 1, col);
            }
         }
      }
   }

   public static void capsule(GuiGraphicsExtractor g, float x, float y, float w, float h, float radius, int col) {
      capsule(g, x, y, w, h, radius, col, col);
   }

   public static void topSpecular(GuiGraphicsExtractor g, float x, float y, float w, float h, float radius, int col) {
      float r = Math.min(radius, h * 0.5F);
      float cy = h * 0.5F;
      float dyc = 0.5F - cy;
      float inset = capInset(dyc, r);
      int x1 = Math.round(x + inset + 1.0F);
      int x2 = Math.round(x + w - inset - 1.0F);
      if (x2 > x1) {
         int yy = Math.round(y) + 1;
         g.fill(x1, yy, x2, yy + 1, col);
      }
   }

   public static void capsuleOutlined(GuiGraphicsExtractor g, float x, float y, float w, float h, float radius, int bodyTop, int bodyBot, int outline) {
      capsule(g, x, y, w, h, radius, outline);
      capsule(g, x + 1.0F, y + 1.0F, w - 2.0F, h - 2.0F, radius - 1.0F, bodyTop, bodyBot);
   }
}
