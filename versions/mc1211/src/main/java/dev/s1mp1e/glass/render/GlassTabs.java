package dev.s1mp1e.glass.render;

import dev.s1mp1e.glass.anim.Fade;
import dev.s1mp1e.glass.anim.Spring;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;

/**
 * Feature B — the creative category tabs as part of the inventory's ONE glass piece (user: "分類跟背包連在一起,
 * 中間不要斷層, 不要是好幾個方塊"). 1.21.1 (DrawContext) port of 26.2's {@code GlassTabs}.
 *
 * <p>The creative body glass is EXTENDED {@link #BAND} GUI px above and below the panel (done in
 * {@code CreativeGlassMixin}), so each tab row is simply the top / bottom band of the same glass sheet — no separate
 * strips, no seam, no square tiles. Inside a band the row is divided into {@link #COLUMNS} equal cells that touch: the
 * icon is centred in its cell (equal gaps all round). The selected tab gets a lifted glass pill (the hotbar
 * selected-slot look, hotbar corner radius via {@link GlassCorners}); a hovered tab a fainter one. The pill SLIDES like
 * the hotbar selector within a row (springs 55/30, ζ1) and CROSS-FADES when the selection jumps to the other row
 * (old out 150 ms, new in 100 ms); the hover pill uses the container slot-hover box motion (in 100 / out 150 ms).
 *
 * <p>Vanilla extracts the unselected tabs BEFORE the body and the selected tab AFTER it, so {@code CreativeGlassMixin}
 * defers every tab tile + icon and {@link #flush}es them when the selected tab is reached, on top of the body glass.
 */
public final class GlassTabs {

    private GlassTabs() {}

    /** Depth of a tab row outside the panel edge (vanilla's top tab sprite starts 28 px above the panel). */
    public static final int BAND = 28;
    /** Vanilla's tab grid per row: 5 left-aligned + 2 right-aligned columns (0..6). */
    public static final int COLUMNS = 7;
    /** Vanilla hit-box width of a tab. */
    public static final int TAB_W = 26;
    private static final int ICON = 16;
    private static final int PILL_INSET = 2;
    private static final float PAD = GlassRenderer.PAD_PANEL;
    // hotbar selector lift 0.12 (selected), fainter for hover — the 26.2 knobs 0xE0 / 0xF4 as neutral-lift fractions.
    private static final float LIFT_SELECTED = 1f - 0xE0 / 255f;   // 0.1216
    private static final float LIFT_HOVER    = 1f - 0xF4 / 255f;   // 0.0431

    private static final ArrayList<int[]> tiles = new ArrayList<>();     // {column, top, selected, hovered}
    private static final ArrayList<Object[]> icons = new ArrayList<>();  // {ItemStack, column, top}

    public static void reset() {
        tiles.clear();
        icons.clear();
    }

    public static float cellW(int imageWidth) {
        return imageWidth / (float) COLUMNS;
    }

    /** Tab hit-box x relative to leftPos: a {@link #TAB_W}-wide box centred in the tab's cell. */
    public static int tabX(int column, int imageWidth) {
        float c = cellW(imageWidth);
        return Math.round(column * c + (c - TAB_W) / 2.0f);
    }

    /** True when {@code (mx,my)} is inside the drawn cell for {@code column}/{@code top} (absolute screen coords). */
    public static boolean inCell(int mx, int my, int leftPos, int topPos, int imageHeight,
                                 int column, int imageWidth, boolean top) {
        int x0 = leftPos + tabX(column, imageWidth);
        int bandTop = top ? topPos - BAND : topPos + imageHeight;
        return mx >= x0 && mx < x0 + TAB_W && my >= bandTop && my < bandTop + BAND;
    }

    public static int iconX(int leftPos, int column, int imageWidth) {
        float c = cellW(imageWidth);
        return leftPos + Math.round(column * c + (c - ICON) / 2.0f);
    }

    public static int iconY(int topPos, int imageHeight, boolean top) {
        int bandTop = top ? topPos - BAND : topPos + imageHeight;
        return bandTop + (BAND - ICON) / 2;
    }

    public static void deferTile(int column, boolean top, boolean selected, boolean hovered) {
        if (tiles.size() < 64) tiles.add(new int[]{column, top ? 1 : 0, selected ? 1 : 0, hovered ? 1 : 0});
    }

    public static void deferIcon(ItemStack stack, int column, boolean top) {
        if (icons.size() < 64) icons.add(new Object[]{stack, column, top ? 1 : 0});
    }

    // ---- motion (hotbar-style slide within a row, cross-fade across rows; slot-hover motion for the hover pill) ----
    private static final float LEAD_W = 55.0f;
    private static final float TRAIL_W = 30.0f;
    private static final float STEP = 1.0f / 120.0f;
    private static Object owner;
    private static long lastNanos;
    private static Spring selLead, selTrail;
    private static int selCol = -1;
    private static boolean selTop;
    private static final Fade selFade = new Fade(1.0f, 100.0f);
    private static float ghostCx;
    private static boolean ghostTop;
    private static final Fade ghostFade = new Fade(0.0f, 150.0f);
    private static Spring hx1, hx2, hy1, hy2;
    private static boolean hoverActive;
    private static final Fade hoverFade = new Fade(0.0f, 100.0f);

    /**
     * Draw the (animated) selected + hovered pills, then every tab icon, on top of the already-drawn body glass.
     * {@code screenOwner} identifies the creative screen instance: a new one snaps all motion (no slide from a stale
     * spot). {@code fade} is the panel open fade (0..1).
     */
    public static void flush(DrawContext ctx, Object screenOwner, int leftPos, int topPos,
                             int imageWidth, int imageHeight, float fade) {
        try {
            long now = System.nanoTime();
            float dt = lastNanos == 0L ? 1.0f / 60.0f : Math.min(0.1f, (now - lastNanos) * 1.0e-9f);
            lastNanos = now;
            if (screenOwner != owner) {
                owner = screenOwner;
                selLead = null;
                hx1 = null;
                hoverActive = false;
                hoverFade.snap(0.0f);
                ghostFade.snap(0.0f);
                selFade.snap(1.0f);
            }
            float c = cellW(imageWidth);
            float hw = c / 2.0f - PILL_INSET;
            float hh = BAND / 2.0f - PILL_INSET;
            int[] sel = null, hov = null;
            for (int[] t : tiles) {
                if (t[2] != 0) sel = t;
                else if (t[3] != 0) hov = t;
            }

            // selected pill: hotbar slide within a row, cross-fade across rows
            if (sel != null) {
                float cx = leftPos + (sel[0] + 0.5f) * c;
                boolean top = sel[1] != 0;
                if (selLead == null) {
                    selLead = new Spring(cx, LEAD_W, 1.0f);
                    selTrail = new Spring(cx, TRAIL_W, 1.0f);
                    selCol = sel[0];
                    selTop = top;
                    selFade.snap(1.0f);
                } else if (top != selTop) {
                    ghostCx = (selLead.value() + selTrail.value()) / 2.0f;
                    ghostTop = selTop;
                    ghostFade.snap(selFade.value());
                    ghostFade.to(0.0f, 150.0f);
                    selLead.snap(cx);
                    selTrail.snap(cx);
                    selFade.snap(0.0f);
                    selFade.to(1.0f, 100.0f);
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
                float cx = leftPos + (hov[0] + 0.5f) * c;
                float cy = bandCy(topPos, imageHeight, hov[1] != 0);
                if (hoverActive && hx1 != null) {
                    retarget(cx, cy);
                } else {
                    if (hx1 != null && hoverFade.value() > 0.05f) {
                        retarget(cx, cy);
                    } else {
                        hx1 = new Spring(cx, LEAD_W, 1.0f);
                        hx2 = new Spring(cx, TRAIL_W, 1.0f);
                        hy1 = new Spring(cy, LEAD_W, 1.0f);
                        hy2 = new Spring(cy, TRAIL_W, 1.0f);
                    }
                    hoverActive = true;
                }
                hoverFade.to(1.0f, 100.0f);
            } else {
                hoverActive = false;
                hoverFade.to(0.0f, 150.0f);
            }

            for (float rem = dt; rem > 0.0f; rem -= STEP) {
                float h = Math.min(rem, STEP);
                if (selLead != null) { selLead.update(h); selTrail.update(h); }
                if (hx1 != null) { hx1.update(h); hx2.update(h); hy1.update(h); hy2.update(h); }
            }

            // Flush any pending DrawContext batch (search box, etc.) into the framebuffer so the immediate-GL pills
            // below land on top of it, then draw the pills (they refract the body's world+dim backdrop grab).
            ctx.draw();
            if (ghostFade.isVisible()) {
                float cy = bandCy(topPos, imageHeight, ghostTop);
                pill(ghostCx - hw, cy - hh, ghostCx + hw, cy + hh, LIFT_SELECTED, fade * ghostFade.value());
            }
            if (hx1 != null && hoverFade.isVisible()) {
                float lo = Math.min(hx1.value(), hx2.value());
                float hi = Math.max(hx1.value(), hx2.value());
                float vlo = Math.min(hy1.value(), hy2.value());
                float vhi = Math.max(hy1.value(), hy2.value());
                pill(lo - hw, vlo - hh, hi + hw, vhi + hh, LIFT_HOVER, fade * hoverFade.value());
            }
            if (sel != null && selLead != null) {
                float lo = Math.min(selLead.value(), selTrail.value());
                float hi = Math.max(selLead.value(), selTrail.value());
                float cy = bandCy(topPos, imageHeight, selTop);
                pill(lo - hw, cy - hh, hi + hw, cy + hh, LIFT_SELECTED, fade * selFade.value());
            }

            // icons on top (batched; the z-translate keeps 3D models above the immediate-GL glass pills)
            MinecraftClient mc = MinecraftClient.getInstance();
            ctx.getMatrices().push();
            ctx.getMatrices().translate(0f, 0f, 100f);
            for (Object[] ic : icons) {
                int col = (Integer) ic[1];
                boolean top = (Integer) ic[2] != 0;
                int ix = iconX(leftPos, col, imageWidth);
                int iy = iconY(topPos, imageHeight, top);
                ctx.drawItem((ItemStack) ic[0], ix, iy);
                ctx.drawItemInSlot(mc.textRenderer, (ItemStack) ic[0], ix, iy);
            }
            ctx.getMatrices().pop();
            ctx.draw();
        } finally {
            reset();
        }
    }

    private static void retarget(float cx, float cy) {
        hx1.setTarget(cx); hx2.setTarget(cx);
        hy1.setTarget(cy); hy2.setTarget(cy);
    }

    private static float bandCy(int topPos, int imageHeight, boolean top) {
        return top ? topPos - BAND / 2.0f : topPos + imageHeight + BAND / 2.0f;
    }

    private static void pill(float fx0, float fy0, float fx1, float fy1, float lift, float fade) {
        int x0 = Math.round(fx0), y0 = Math.round(fy0), x1 = Math.round(fx1), y1 = Math.round(fy1);
        if (x1 <= x0 || y1 <= y0 || fade <= 0.004f) return;
        float corner = GlassCorners.hotbarCornerFrac(x1 - x0, y1 - y0);
        GlassRenderer.glass(x0, y0, x1, y1, PAD, corner, lift, fade, GlassRenderer.FROST_NONE);
    }
}
