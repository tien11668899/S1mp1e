package com.seagull.liquidglass.client.render;

import dev.s1mp1e.client.gui.GlassWidgets;
import net.minecraft.client.gui.GuiGraphics;

/**
 * The selected row of a selection list as a glass highlight on the button material (the same glass as buttons and tabs)
 * with the hotbar corner — brighter while the list has focus. Drawn by {@code SelectionGlassMixin} in place of vanilla's
 * box, and by {@code SelectionListGlideMixin} at the gliding position.
 */
public final class SelectionGlass {
   private SelectionGlass() {}

   /** True when the glass can be drawn this frame (else vanilla's box stays). */
   public static boolean usable() {
      return GlassPipeline.ensureReady() && GlassPipeline.btnUsable();
   }

   public static void draw(GuiGraphics g, float x0, float y0, float x1, float y1, boolean focused) {
      if (x1 <= x0 || y1 <= y0) return;
      float half = Math.min(x1 - x0, y1 - y0) / 2.0F;
      float corner = Math.min(1.0F, GlassCorners.HOTBAR_RADIUS / Math.max(1.0F, half));
      GlassWidgets.capsule(g, x0, y0, x1, y1, corner, focused ? 0.81F : 0.55F, 1.0F, true);
   }
}
