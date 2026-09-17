package com.seagull.liquidglass.client.render;

import com.seagull.liquidglass.client.animation.Fade;
import com.seagull.liquidglass.client.mixin.GuiGraphicsExtractorAccessor;
import java.util.HashSet;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.core.NonNullList;
import net.minecraft.world.inventory.Slot;

public final class GlassPanels {
   private static Object curScreen;
   private static final Fade openFade = new Fade(0.0F, 150.0F);

   private GlassPanels() {
   }

   public static int fadeByte() {
      return Math.round(openFade.value() * 255.0F) & 0xFF;
   }

   public static boolean panel(GuiGraphicsExtractor g, AbstractContainerScreen<?> screen, int x, int y, int w, int h) {
      if (GlassPipeline.ensureReady() && GlassPipeline.usable()) {
         if (curScreen != screen) {
            curScreen = screen;
            openFade.snap(0.0F);
            openFade.to(1.0F);
         }

         int b = fadeByte();
         PanelGhost.beginFrame();
         PanelGhost.remember(x, y, w, h);
         GuiRenderState rs = ((GuiGraphicsExtractorAccessor)g).liquidglass$guiRenderState();
         TextureSetup ts = TextureSetup.singleTexture(GlassPipeline.backdropView(), GlassPipeline.sampler());
         rs.addGuiElement(new GlassRectRenderState(GlassPipeline.glass(), ts, g.pose(), x, y, x + w, y + h, 12, -2143420672 | b, null));
         if (GlassPipeline.lineUsable()) {
            TextureSetup none = TextureSetup.noTexture();
            NonNullList<Slot> slots = screen.getMenu().slots;
            HashSet<Long> pos = new HashSet<>();

            for (Slot s : slots) {
               pos.add((long)s.x << 32 | (long)s.y & 4294967295L);
            }

            for (Slot s : slots) {
               int mask = 0;
               if (pos.contains((long)(s.x + 18) << 32 | (long)s.y & 4294967295L)) {
                  mask |= 1;
               }

               if (pos.contains((long)(s.x - 18) << 32 | (long)s.y & 4294967295L)) {
                  mask |= 2;
               }

               if (pos.contains((long)s.x << 32 | (long)(s.y + 18) & 4294967295L)) {
                  mask |= 4;
               }

               if (pos.contains((long)s.x << 32 | (long)(s.y - 18) & 4294967295L)) {
                  mask |= 8;
               }

               int lcol = mask * 17 << 24 | 16776960 | b;
               rs.addGuiElement(
                  new GlassRectRenderState(GlassPipeline.line(), none, g.pose(), x + s.x - 1, y + s.y - 1, x + s.x + 17, y + s.y + 17, 0, lcol, null)
               );
            }
         }

         return true;
      } else {
         return false;
      }
   }
}
