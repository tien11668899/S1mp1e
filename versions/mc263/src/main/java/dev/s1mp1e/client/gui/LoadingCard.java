package dev.s1mp1e.client.gui;

import com.seagull.liquidglass.client.render.GlassCorners;
import com.seagull.liquidglass.client.render.GlassPipeline;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The one liquid-glass "status card" shared by every text loading screen (progress / connect / waiting …). Callers pass
 * the TIGHT bounding box of the screen's own text; this pads it uniformly ({@link #PAD_X} / {@link #PAD_Y}, the same on
 * every screen) and draws it with the one unified corner radius ({@link GlassCorners#HOTBAR_RADIUS}), so every loading
 * card has identical spacing and identical corners regardless of how long its text is.
 */
public final class LoadingCard {
   private LoadingCard() {
   }

   /** Uniform inner padding around the text, and the scrim, shared by every loading card. */
   public static final float PAD_X = 26.0F;
   public static final float PAD_Y = 16.0F;
   /** Font line height, and the one gap between the card's rows (text → loader → text), the 4pt grid's 8. */
   public static final float TEXT_H = 9.0F;
   public static final float GAP = 8.0F;
   private static final float SCRIM = 0.28F;
   /** A short one-word status still gets a balanced card (min half-width before padding). */
   private static final float MIN_HALF_W = 46.0F;

   /** Draw the card around a tight text box in screen space (x0,y0 = top-left of the text, x1,y1 = bottom-right). */
   public static void box(GuiGraphicsExtractor g, float x0, float y0, float x1, float y1) {
      if (!GlassPipeline.ensureReady() || !GlassPipeline.usable()) {
         return;
      }
      float cx = (x0 + x1) * 0.5F;
      float halfW = Math.max((x1 - x0) * 0.5F, MIN_HALF_W);
      GlassWidgets.panel(g, cx - halfW - PAD_X, y0 - PAD_Y, cx + halfW + PAD_X, y1 + PAD_Y,
            1.0F, GlassCorners.HOTBAR_RADIUS, SCRIM);
   }
}
