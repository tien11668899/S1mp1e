package com.seagull.liquidglass.client.render;

import com.seagull.liquidglass.client.animation.Fade;
import com.seagull.liquidglass.client.animation.Spring;
import com.seagull.liquidglass.client.mixin.GuiGraphicsExtractorAccessor;
import java.util.ArrayList;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.world.item.ItemStack;

/**
 * Creative-inventory category tabs as part of the inventory's ONE glass piece (user: "分類跟背包連在一起, 中間不要斷層,
 * 不要是好幾個方塊").
 *
 * <p>The creative body glass itself is extended {@link #BAND} GUI px above and below the panel
 * ({@code GlassPanels.panel(..., BAND, BAND)}), so each tab row is simply the top / bottom band of the same glass sheet -
 * no separate strips, no seam, no square tiles. Inside a band the row is divided into {@link #COLUMNS} equal cells that
 * touch each other (vanilla's 5 left + 2 right-aligned tabs map to cells 0..6 by {@code column()}):
 * <ul>
 *   <li>vertically the icon is centred in the band, so the gap above and below it is equal;</li>
 *   <li>horizontally the icon is centred in its cell, so the gaps left and right are equal and the cells run on
 *       continuously (the glass under neighbouring tabs is one surface).</li>
 * </ul>
 * The selected tab gets a lifted glass pill inset in its cell (the hotbar selected-slot look, hotbar corner radius via
 * {@link GlassCorners}); a hovered tab a fainter one. {@code CreativeGlassMixin} moves vanilla's tab hit boxes
 * ({@code getTabX}) to the same cells, so what is drawn is what is clicked.
 *
 * <p>Vanilla extracts the unselected tabs BEFORE the body and the selected tab AFTER it; everything here is therefore
 * deferred per frame and flushed when the selected tab is extracted, on top of the body glass.
 */
public final class GlassTabs {
   private GlassTabs() {
   }

   /** Depth of a tab row outside the panel edge, GUI px (vanilla's top tab sprite starts 28 px above the panel). */
   public static final int BAND = 28;
   /** Vanilla's tab grid per row: 5 left-aligned + 2 right-aligned columns. */
   public static final int COLUMNS = 7;
   /** Vanilla hit-box width of a tab. */
   public static final int TAB_W = 26;
   private static final int ICON = 16;
   private static final int PILL_INSET = 2;
   private static final int FROST = 0xFF;
   private static final int LIFT_SELECTED = 0xE0;   // the hotbar selected pill's lift
   private static final int LIFT_HOVER = 0xF4;      // faint
   private static final int PAD = 12;

   private static final ArrayList<int[]> tiles = new ArrayList<>();     // {column, top(0/1), selected(0/1), hovered(0/1)}
   private static final ArrayList<Object[]> icons = new ArrayList<>();  // {ItemStack, column, top(0/1)}

   public static void reset() {
      tiles.clear();
      icons.clear();
   }

   public static float cellW(int imageWidth) {
      return imageWidth / (float)COLUMNS;
   }

   /** Tab hit-box x relative to leftPos: a {@link #TAB_W}-wide box centred in the tab's cell. */
   public static int tabX(int column, int imageWidth) {
      float c = cellW(imageWidth);
      return Math.round(column * c + (c - TAB_W) / 2.0F);
   }

   public static int iconX(int leftPos, int column, int imageWidth) {
      float c = cellW(imageWidth);
      return leftPos + Math.round(column * c + (c - ICON) / 2.0F);
   }

   public static int iconY(int topPos, int imageHeight, boolean top) {
      int bandTop = top ? topPos - BAND : topPos + imageHeight;
      return bandTop + (BAND - ICON) / 2;
   }

   public static void deferTile(int column, boolean top, boolean selected, boolean hovered) {
      if (tiles.size() < 64) {
         tiles.add(new int[]{column, top ? 1 : 0, selected ? 1 : 0, hovered ? 1 : 0});
      }
   }

   public static void deferIcon(ItemStack stack, int column, boolean top) {
      if (icons.size() < 64) {
         icons.add(new Object[]{stack, column, top ? 1 : 0});
      }
   }

   // ---- motion (user: "分類的框框要像hotbar滑過去, 中間過渡就像滑鼠懸停在背包上面的框框淡入淡出一樣") ----
   // Selected pill: the hotbar pill's two-spring slide (lead 55 / trail 30, zeta 1) along its row; a switch to the other
   // row cross-fades instead (old pill out 150 ms, new pill in 100 ms) rather than dragging across the inventory.
   // Hover pill: the container slot-hover box's motion exactly (ContainerCloseGhostMixin): glide with the same springs
   // while still visible, otherwise appear in place; fade in 100 ms, out 150 ms.
   private static final float LEAD_W = 55.0F;
   private static final float TRAIL_W = 30.0F;
   private static final float STEP = 1.0F / 120.0F;
   private static Object owner;
   private static long lastNanos;
   private static Spring selLead;
   private static Spring selTrail;
   private static int selCol = -1;
   private static boolean selTop;
   private static final Fade selFade = new Fade(1.0F, 100.0F);
   private static float ghostCx;
   private static boolean ghostTop;
   private static final Fade ghostFade = new Fade(0.0F, 150.0F);
   private static Spring hx1;
   private static Spring hx2;
   private static Spring hy1;
   private static Spring hy2;
   private static boolean hoverActive;
   private static final Fade hoverFade = new Fade(0.0F, 100.0F);

   /**
    * Draw the (animated) selected and hovered pills, then every tab icon, on top of the already-enqueued body glass.
    * {@code screenOwner} identifies the creative screen instance: a new one snaps all motion (no slide from a stale spot).
    */
   public static void flush(GuiGraphicsExtractor g, Object screenOwner, int leftPos, int topPos, int imageWidth, int imageHeight, int fadeByte) {
      try {
         long now = System.nanoTime();
         float dt = lastNanos == 0L ? 1.0F / 60.0F : Math.min(0.1F, (now - lastNanos) * 1.0E-9F);
         lastNanos = now;
         if (screenOwner != owner) {
            owner = screenOwner;
            selLead = null;
            hx1 = null;
            hoverActive = false;
            hoverFade.snap(0.0F);
            ghostFade.snap(0.0F);
            selFade.snap(1.0F);
         }
         float c = cellW(imageWidth);
         float hw = c / 2.0F - PILL_INSET;
         float hh = BAND / 2.0F - PILL_INSET;
         int[] sel = null;
         int[] hov = null;
         for (int[] t : tiles) {
            if (t[2] != 0) {
               sel = t;
            } else if (t[3] != 0) {
               hov = t;
            }
         }

         // selected pill: hotbar slide within a row, cross-fade across rows
         if (sel != null) {
            float cx = leftPos + (sel[0] + 0.5F) * c;
            boolean top = sel[1] != 0;
            if (selLead == null) {
               selLead = new Spring(cx, LEAD_W, 1.0F);
               selTrail = new Spring(cx, TRAIL_W, 1.0F);
               selCol = sel[0];
               selTop = top;
               selFade.snap(1.0F);
            } else if (top != selTop) {
               ghostCx = (selLead.value() + selTrail.value()) / 2.0F;
               ghostTop = selTop;
               ghostFade.snap(selFade.value());
               ghostFade.to(0.0F, 150.0F);
               selLead.snap(cx);
               selTrail.snap(cx);
               selFade.snap(0.0F);
               selFade.to(1.0F, 100.0F);
               selTop = top;
               selCol = sel[0];
            } else if (sel[0] != selCol) {
               selLead.setTarget(cx);
               selTrail.setTarget(cx);
               selCol = sel[0];
            }
         }

         // hover pill: the slot-hover box motion
         if (hov != null) {
            float cx = leftPos + (hov[0] + 0.5F) * c;
            float cy = bandCy(topPos, imageHeight, hov[1] != 0);
            if (hoverActive && hx1 != null) {
               retarget(cx, cy);
            } else {
               if (hx1 != null && hoverFade.value() > 0.05F) {
                  retarget(cx, cy);
               } else {
                  hx1 = new Spring(cx, LEAD_W, 1.0F);
                  hx2 = new Spring(cx, TRAIL_W, 1.0F);
                  hy1 = new Spring(cy, LEAD_W, 1.0F);
                  hy2 = new Spring(cy, TRAIL_W, 1.0F);
               }
               hoverActive = true;
            }
            hoverFade.to(1.0F, 100.0F);
         } else {
            hoverActive = false;
            hoverFade.to(0.0F, 150.0F);
         }

         for (float rem = dt; rem > 0.0F; rem -= STEP) {
            float h = Math.min(rem, STEP);
            if (selLead != null) {
               selLead.update(h);
               selTrail.update(h);
            }
            if (hx1 != null) {
               hx1.update(h);
               hx2.update(h);
               hy1.update(h);
               hy2.update(h);
            }
         }

         int fb = fadeByte & 0xFF;
         if (ghostFade.isVisible()) {
            float cy = bandCy(topPos, imageHeight, ghostTop);
            pill(g, ghostCx - hw, cy - hh, ghostCx + hw, cy + hh, LIFT_SELECTED, Math.round(fb * ghostFade.value()));
         }
         if (hx1 != null && hoverFade.isVisible()) {
            float lo = Math.min(hx1.value(), hx2.value());
            float hi = Math.max(hx1.value(), hx2.value());
            float vlo = Math.min(hy1.value(), hy2.value());
            float vhi = Math.max(hy1.value(), hy2.value());
            pill(g, lo - hw, vlo - hh, hi + hw, vhi + hh, LIFT_HOVER, Math.round(fb * hoverFade.value()));
         }
         if (sel != null && selLead != null) {
            float lo = Math.min(selLead.value(), selTrail.value());
            float hi = Math.max(selLead.value(), selTrail.value());
            float cy = bandCy(topPos, imageHeight, selTop);
            pill(g, lo - hw, cy - hh, hi + hw, cy + hh, LIFT_SELECTED, Math.round(fb * selFade.value()));
         }
         for (Object[] ic : icons) {
            int col = (Integer)ic[1];
            boolean top = (Integer)ic[2] != 0;
            g.item((ItemStack)ic[0], iconX(leftPos, col, imageWidth), iconY(topPos, imageHeight, top));
         }
      } finally {
         reset();
      }
   }

   private static void retarget(float cx, float cy) {
      hx1.setTarget(cx);
      hx2.setTarget(cx);
      hy1.setTarget(cy);
      hy2.setTarget(cy);
   }

   private static float bandCy(int topPos, int imageHeight, boolean top) {
      return top ? topPos - BAND / 2.0F : topPos + imageHeight + BAND / 2.0F;
   }

   private static void pill(GuiGraphicsExtractor g, float fx0, float fy0, float fx1, float fy1, int lift, int fadeByte) {
      int x0 = Math.round(fx0);
      int y0 = Math.round(fy0);
      int x1 = Math.round(fx1);
      int y1 = Math.round(fy1);
      if (x1 <= x0 || y1 <= y0 || (fadeByte & 0xFF) == 0) {
         return;
      }
      int color = (FROST << 24) | (GlassCorners.knobByte(x1 - x0, y1 - y0) << 16) | ((lift & 0xFF) << 8) | (fadeByte & 0xFF);
      GuiRenderState rs = ((GuiGraphicsExtractorAccessor)g).liquidglass$guiRenderState();
      TextureSetup ts = TextureSetup.singleTexture(GlassPipeline.backdropView(), GlassPipeline.sampler());
      rs.addGuiElement(new GlassRectRenderState(GlassPipeline.glass(), ts, g.pose(), x0, y0, x1, y1, PAD, color, null));
   }
}
