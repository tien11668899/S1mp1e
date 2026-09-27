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
      return panel(g, screen, x, y, w, h, 0, 0);
   }

   /**
    * As {@link #panel(GuiGraphicsExtractor, AbstractContainerScreen, int, int, int, int)}, but the ONE glass sheet reaches
    * {@code extTop} GUI px above and {@code extBottom} below the panel (the creative tab rows, fused into the body - no
    * seam). The slot lattice stays at the panel's slots; the corner radius stays the unextended panel's (the knob is
    * rescaled for the taller sheet, since the shader radius scales with the short side).
    */
   public static boolean panel(GuiGraphicsExtractor g, AbstractContainerScreen<?> screen, int x, int y, int w, int h, int extTop, int extBottom) {
      if (GlassPipeline.ensureReady() && GlassPipeline.usable()) {
         if (curScreen != screen) {
            curScreen = screen;
            openFade.snap(0.0F);
            openFade.to(1.0F);
         }

         int b = fadeByte();
         int gy0 = y - extTop;
         int gy1 = y + h + extBottom;
         int knobs = -2143420672;   // 0x803DFF00: frost .5, corner 0x3D, no lift
         if (extTop != 0 || extBottom != 0) {
            // keep the unextended panel's absolute radius: r = min(w,h)/4 * corner  ->  corner' = 4r / min(w, h')
            float r = Math.min(w, h) / 4.0F * (0x3D / 255.0F);
            int corner = Math.round(Math.min(1.0F, 4.0F * r / Math.min(w, gy1 - gy0)) * 255.0F) & 0xFF;
            knobs = (knobs & 0xFF00FFFF) | (corner << 16);
         }
         PanelGhost.beginFrame();
         PanelGhost.remember(x, gy0, w, gy1 - gy0);
         GuiRenderState rs = ((GuiGraphicsExtractorAccessor)g).liquidglass$guiRenderState();
         TextureSetup ts = TextureSetup.singleTexture(GlassPipeline.backdropView(), GlassPipeline.sampler());
         rs.addGuiElement(new GlassRectRenderState(GlassPipeline.glass(), ts, g.pose(), x, gy0, x + w, gy1, 12, knobs | b, null));
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
