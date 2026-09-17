package com.seagull.liquidglass.client.render;

import com.seagull.liquidglass.client.animation.Fade;
import com.seagull.liquidglass.client.animation.Spring;
import com.seagull.liquidglass.client.mixin.GuiGraphicsExtractorAccessor;
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

   public static void ghostPass(GuiGraphicsExtractor g) {
      if (activeThisFrame) {
         activeThisFrame = false;
      } else if (panelFade.isVisible() && sx != null && GlassPipeline.usable()) {
         panelFade.to(0.0F);
         if (panelFade.isVisible()) {
            drawQuad(g);
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
      TextureSetup ts = TextureSetup.singleTexture(GlassPipeline.backdropView(), GlassPipeline.sampler());
      ((GuiGraphicsExtractorAccessor)g)
         .liquidglass$guiRenderState()
         .addGuiElement(new GlassRectRenderState(GlassPipeline.glass(), ts, g.pose(), x, y, x + w, y + h, 8, col, null));
   }
}
