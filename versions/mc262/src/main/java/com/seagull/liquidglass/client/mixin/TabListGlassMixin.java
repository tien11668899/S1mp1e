package com.seagull.liquidglass.client.mixin;

import com.seagull.liquidglass.client.render.GlassPipeline;
import com.seagull.liquidglass.client.render.GlassSurface;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The player tab list becomes liquid glass. {@code PlayerTabOverlay.extractRenderState} paints its backgrounds with four
 * {@code g.fill} calls, drawn in insertion order:
 * <ol>
 *   <li>header background (only when a header is set),</li>
 *   <li>the player-list panel,</li>
 *   <li>the per-row name stripe (once per row, height 8), and</li>
 *   <li>footer background (only when a footer is set).</li>
 * </ol>
 * Each fill is redirected: the three tall structural panels (header / list / footer) become refracting glass plates with a
 * grey readability scrim, and the short per-row stripe becomes a thinned scrim so the row striping stays visible but gentle
 * over the glass. The ping bars, hearts and names ({@code blitSprite} / text) are left untouched so they stay readable
 * (LOOK SPEC). When the glass pipeline is not usable every fill falls back to the untouched vanilla draw.
 */
@Mixin(PlayerTabOverlay.class)
public abstract class TabListGlassMixin {

   /** Rects taller than this are structural panels; the short one is the per-row name stripe. */
   private static final int ROW_MAX_H = 10;
   /** Grey readability scrim under the names on the structural panels. */
   private static final int PANEL_SCRIM = 0x66101018;

   @Redirect(
      method = "extractRenderState",
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;fill(IIIII)V"
      )
   )
   private void lg$glassFill(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color) {
      if (!(GlassPipeline.ensureReady() && GlassPipeline.usable())) {
         g.fill(x0, y0, x1, y1, color);
         return;
      }
      if (y1 - y0 <= ROW_MAX_H) {
         // Per-row name stripe -> a softened scrim (keep row striping, gentle over glass).
         int a = Math.round((color >>> 24 & 0xFF) * 0.5F) & 0xFF;
         GlassSurface.scrim(g, x0, y0, x1, y1, 2.0F, a << 24 | color & 0xFFFFFF);
      } else {
         // Structural header / list / footer panel -> refracting glass plate + readability scrim.
         GlassSurface.plateOrPaint(g, x0, y0, x1, y1, 0xFF, 0x99101018);
         GlassSurface.scrim(g, x0, y0, x1, y1, 0.0F, PANEL_SCRIM);
      }
   }
}
