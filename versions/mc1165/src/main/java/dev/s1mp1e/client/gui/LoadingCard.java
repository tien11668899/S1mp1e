package dev.s1mp1e.client.gui;

import dev.s1mp1e.glass.render.GlassCorners;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.util.math.MatrixStack;

/**
 * The one liquid-glass "status card" shared by every text loading screen (progress / connect / …). Callers pass the
 * TIGHT bounding box of the screen's own text; this pads it uniformly ({@link #PAD_X} / {@link #PAD_Y}, the same on
 * every screen) and draws it with the one unified corner radius ({@link GlassCorners#HOTBAR_RADIUS}), so every loading
 * card has identical spacing and identical corners regardless of how long its text is.
 *
 * <p>This is the 1.21.1 (MatrixStack / core-profile) port of 26.2's {@code LoadingCard}. Same layout constants and the
 * same 0.28 scrim, but drawn immediately through {@link GlassRenderer} — a real refracting {@link GlassRenderer#glass}
 * body plus a dark readability {@link GlassRenderer#roundRect} scrim — instead of 26.2's deferred GuiRenderState panel.
 * The caller must have flushed the batched background and grabbed a fresh backdrop ({@code ctx.draw()} +
 * {@link SceneCapture#grabNow()}) first, so the card refracts the (dimmed) background and the screen's text, drawn
 * afterward through the MatrixStack, still flushes on TOP of the card.
 */
public final class LoadingCard {
   private LoadingCard() {
   }

   /** Uniform inner padding around the text, and the scrim, shared by every loading card. */
   public static final float PAD_X = 26.0F;
   public static final float PAD_Y = 16.0F;
   /** Font line height, and the one gap between the card's rows (text -> loader -> text), the 4pt grid's 8. */
   public static final float TEXT_H = 9.0F;
   public static final float GAP = 8.0F;
   /** Dark readability scrim over the glass body (26.2 used 0.28), in the config panel's neutral dark tone. */
   private static final float SCRIM = 0.28F;
   private static final int SCRIM_RGB = 0x1C1C1E;
   /** A short one-word status still gets a balanced card (min half-width before padding). */
   private static final float MIN_HALF_W = 46.0F;

   /** Draw the card around a tight text box in screen space (x0,y0 = top-left of the text, x1,y1 = bottom-right). */
   public static void box(MatrixStack ctx, float x0, float y0, float x1, float y1) {
      if (!GlassProgram.ensureReady() || !GlassProgram.usable()) {
         return;
      }
      float cx = (x0 + x1) * 0.5F;
      float halfW = Math.max((x1 - x0) * 0.5F, MIN_HALF_W);
      panel(ctx, cx - halfW - PAD_X, y0 - PAD_Y, cx + halfW + PAD_X, y1 + PAD_Y, 1.0F);
   }

   /**
    * The card surface: refractive glass at the hotbar corner radius with a dark readability scrim over it. Mirrors
    * {@code GlassWidgets.panel} but with the {@link GlassCorners#HOTBAR_RADIUS} corner (rule R2 for new glass pieces)
    * and the 0.28 scrim of 26.2's loading cards, rather than the config panel's large corner and whisper scrim.
    */
   private static void panel(MatrixStack ctx, float x0, float y0, float x1, float y1, float alpha) {
      float w = x1 - x0, h = y1 - y0;
      if (GlassProgram.usable() && SceneCapture.hasBackdrop()) {
         GlassRenderer.glass(x0, y0, x1, y1, GlassRenderer.PAD_PANEL,
               GlassCorners.hotbarCornerFrac(w, h), 0.0F, alpha, GlassRenderer.FROST_PANEL);
         if (GlassProgram.roundUsable()) {
            GlassRenderer.roundRect(x0, y0, x1, y1, GlassCorners.hotbarRadiusPx(w, h),
                  (clampByte(alpha * SCRIM) << 24) | SCRIM_RGB);
         }
         return;
      }
      // No glass program / no backdrop: an opaque frosted dark fallback so the text still seats.
      if (GlassProgram.roundUsable()) {
         GlassRenderer.roundRect(x0, y0, x1, y1, GlassCorners.hotbarRadiusPx(w, h),
               (clampByte(alpha * 0.58F) << 24) | SCRIM_RGB);
      }
   }

   private static int clampByte(float a) {
      int v = Math.round(a * 255.0F);
      return v < 0 ? 0 : (v > 255 ? 255 : v);
   }
}
