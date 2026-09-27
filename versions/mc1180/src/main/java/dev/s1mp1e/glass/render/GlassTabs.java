package dev.s1mp1e.glass.render;

import dev.s1mp1e.glass.anim.Fade;
import dev.s1mp1e.glass.anim.Spring;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;

/**
 * Feature (B): the creative inventory tab rows fused into the body glass as ONE sheet, with a hotbar-style selection
 * pill that slides within a row and cross-fades across rows, and a fainter hover pill on the container slot-hover
 * motion. 1.18.2 ({@link MatrixStack} core-profile) port of the 26.2 {@code GlassTabs} intent, adapted to this version's
 * tab layout (6 columns per row, two rows, no pagination — identical to the 1.19.2 sibling's legacy {@link ItemGroup}
 * array API).
 *
 * <h3>Fused sheet</h3>
 * The body panel (px,py .. px+w,py+h) is extended by {@link #BAND} px above and below into one glass rect that both tab
 * rows sit inside, so there is no seam between the tabs and the body. The sheet keeps the body panel's ABSOLUTE corner
 * radius: since {@code radius = min(halfW,halfH)*0.5*corner} and the sheet is taller, the corner knob is rescaled so the
 * drawn radius equals the un-extended panel's (R2 leaves EXISTING surfaces' radii unchanged — the body IS an existing
 * surface, so its radius is preserved, not swapped for the hotbar radius). The NEW pieces (the pills) DO use the hotbar
 * radius via {@link GlassCorners}.
 *
 * <h3>Cells</h3>
 * The band is divided into {@link #COLUMNS} equal, touching cells; each tab icon is centred in its cell (equal
 * left/right and top/bottom gaps). Hit-testing ({@link #hit}) uses the same cell geometry, so what is drawn is what is
 * clicked.
 *
 * <h3>Motion</h3>
 * The selected pill is two springs (lead ω55 / trail ω30, ζ1 — the exact hotbar selection-pill rig) sliding along a
 * row; a row switch cross-fades (old pill fades out 150 ms as a ghost, the new pill snaps to the new cell and fades in
 * 100 ms) instead of dragging across the body. The hover pill uses the same two-axis rig on x AND y with a 100 ms
 * fade-in / 150 ms fade-out. A fresh screen instance snaps all motion (a new {@code GlassTabs} is created per screen).
 */
public final class GlassTabs {

    /** Row depth outside the panel edge (GUI px). Vanilla's top tab sprite protrudes 28 px above the panel. */
    public static final int BAND = 32;   // #8 ≈ cell width (195/6=32.5): square cells → equal top/bottom/left/right spacing
    /** Tabs per row in 1.18.2 (columns 0..5). */
    public static final int COLUMNS = 6;

    // Selection pill (hotbar rig).
    private final Spring selLead = new Spring(0f, Spring.OMEGA_SNAP, Spring.DAMPING);
    private final Spring selTrail = new Spring(0f, Spring.OMEGA_MED, Spring.DAMPING);
    private final Fade selFade = new Fade(1f, 100f);   // new-pill fade-in on a row switch
    private int selRow = -1;                            // current pill row (0 top, 1 bottom); -1 = uninitialised
    private boolean selReady;
    // Cross-fade ghost (the pill leaving the old row).
    private boolean ghostActive;
    private float ghostCx, ghostHalfW, ghostCy, ghostHalfH;
    private final Fade ghostFade = new Fade(0f, 150f);

    // Hover pill (two-axis hotbar rig).
    private final Spring hovX1 = new Spring(0f, Spring.OMEGA_SNAP, Spring.DAMPING);
    private final Spring hovX2 = new Spring(0f, Spring.OMEGA_MED, Spring.DAMPING);
    private final Spring hovY1 = new Spring(0f, Spring.OMEGA_SNAP, Spring.DAMPING);
    private final Spring hovY2 = new Spring(0f, Spring.OMEGA_MED, Spring.DAMPING);
    private boolean hovActive, hovReady;
    private final Fade hovFade = new Fade(0f, 150f);

    private long lastNanos;

    // ---- geometry (all in GUI px, pose identity) --------------------------

    private static float cellW(int w) { return w / (float) COLUMNS; }
    private static float cellCenterX(int px, int w, int col) { return px + (col + 0.5f) * cellW(w); }
    private static float bandCenterY(int py, int h, boolean topRow) {
        return topRow ? py - BAND / 2f : py + h + BAND / 2f;
    }

    /** True if (mx,my) is inside {@code group}'s fused cell — the isClickInTab replacement. */
    public boolean hit(ItemGroup group, double mx, double my, int px, int py, int w, int h) {
        // #5 — 1.18.2's CreativeInventoryScreen.mouseClicked calls isClickInTab with coordinates RELATIVE to the
        // panel origin ({@code mouseX - this.x}, {@code mouseY - this.y}); the fused cells must be tested in that
        // same relative space, or every tab click (and the vanilla tab tooltip, which shares isClickInTab) misses.
        int col = group.getColumn();
        boolean top = group.isTopRow();
        float cw = cellW(w);
        float x0 = col * cw, x1 = x0 + cw;
        float y0 = top ? -BAND : h;
        float y1 = y0 + BAND;
        return mx >= x0 && mx < x1 && my >= y0 && my < y1;
    }

    // ---- render -----------------------------------------------------------

    /**
     * Draw the fused sheet, the sliding/fading selection pill, the hover pill and every tab icon centred in its cell.
     * The caller (creative glass mixin) has already grabbed a fresh backdrop and driven the open fade; {@code fade} is
     * that open-fade value and {@code sheetCornerKnob} the rescaled body corner.
     */
    public void render(MatrixStack matrices, int px, int py, int w, int h, float fade, float sheetCornerKnob,
                       int selectedTab, double mouseX, double mouseY, ItemRenderer ir, TextRenderer font) {
        long now = System.nanoTime();
        float dt = (lastNanos == 0L) ? (1f / 60f) : Math.min(0.1f, (now - lastNanos) * 1e-9f);
        lastNanos = now;

        // 1) fused sheet — one glass rect covering both bands + the body, body corner radius preserved.
        int sy0 = py - BAND, sy1 = py + h + BAND;
        GlassRenderer.glass(px, sy0, px + w, sy1, GlassRenderer.PAD_PANEL, sheetCornerKnob, 0f, fade,
                GlassRenderer.FROST_PANEL);

        // 2) selection pill motion.
        ItemGroup sel = s1mp1e$group(selectedTab);
        if (sel != null) {
            int col = sel.getColumn();
            boolean top = sel.isTopRow();
            int row = top ? 0 : 1;
            float cx = cellCenterX(px, w, col);
            float cy = bandCenterY(py, h, top);
            // S9P6 — the selected/hover pill is a SQUARE centred on the tab icon: side = min(cell width, band
            // depth) - 2*inset, so width == height. The slide still stretches it horizontally between lead/trail.
            float half = Math.min(cellW(w), (float) BAND) / 2f - 2f;
            float halfW = half;
            float halfH = half;
            if (!selReady) {
                selLead.snap(cx); selTrail.snap(cx); selRow = row; selReady = true; selFade.snap(1f);
            } else if (row != selRow) {
                // cross-fade: leave a ghost at the old pill, snap the pill to the new cell, fade it in.
                float lo = Math.min(selLead.value(), selTrail.value());
                float hi = Math.max(selLead.value(), selTrail.value());
                ghostActive = true;
                ghostCx = (lo + hi) / 2f;
                ghostHalfW = Math.min(cellW(w), (float) BAND) / 2f - 2f;
                ghostCy = bandCenterY(py, h, selRow == 0);
                ghostHalfH = halfH;
                ghostFade.snap(1f); ghostFade.to(0f, 150f);
                selLead.snap(cx); selTrail.snap(cx); selRow = row;
                selFade.snap(0f); selFade.to(1f, 100f);
            } else {
                selLead.setTarget(cx); selTrail.setTarget(cx);
            }
            selLead.advance(dt); selTrail.advance(dt);

            // ghost (old row) first, under the live pill.
            if (ghostActive) {
                float gf = ghostFade.value();
                if (gf <= 0.01f && ghostFade.isIdle()) ghostActive = false;
                else s1mp1e$pill(ghostCx - ghostHalfW, ghostCy - ghostHalfH, ghostCx + ghostHalfW, ghostCy + ghostHalfH,
                        gf, 0.12f);
            }
            float lo = Math.min(selLead.value(), selTrail.value());
            float hi = Math.max(selLead.value(), selTrail.value());
            s1mp1e$pill(lo - halfW, cy - halfH, hi + halfW, cy + halfH, selFade.value(), 0.12f);
        }

        // 3) hover pill — fainter, slot-hover glide, only when hovering a non-selected cell.
        ItemGroup hov = s1mp1e$hovered(mouseX, mouseY, px, py, w, h);
        boolean hovering = hov != null && (sel == null || hov.getIndex() != sel.getIndex());
        if (hovering) {
            float cx = cellCenterX(px, w, hov.getColumn());
            float cy = bandCenterY(py, h, hov.isTopRow());
            if (!hovReady || (!hovActive && hovFade.value() <= 0.05f)) {
                hovX1.snap(cx); hovX2.snap(cx); hovY1.snap(cy); hovY2.snap(cy); hovReady = true;
            } else {
                hovX1.setTarget(cx); hovX2.setTarget(cx); hovY1.setTarget(cy); hovY2.setTarget(cy);
            }
            hovActive = true; hovFade.to(1f, 100f);
        } else {
            hovActive = false; hovFade.to(0f, 150f);
        }
        if (hovReady && hovFade.value() > 0.01f) {
            hovX1.advance(dt); hovX2.advance(dt); hovY1.advance(dt); hovY2.advance(dt);
            float lox = Math.min(hovX1.value(), hovX2.value()), hix = Math.max(hovX1.value(), hovX2.value());
            float loy = Math.min(hovY1.value(), hovY2.value()), hiy = Math.max(hovY1.value(), hovY2.value());
            float half = Math.min(cellW(w), (float) BAND) / 2f - 2f;
            float halfW = half, halfH = half;
            s1mp1e$pill(lox - halfW, loy - halfH, hix + halfW, hiy + halfH, hovFade.value(), 0.043f);
        }

        // 4) every tab icon centred in its cell (both rows), on top of the sheet + pills.
        for (ItemGroup g : ItemGroup.GROUPS) {
            if (g == null) continue;
            float cx = cellCenterX(px, w, g.getColumn());
            float cy = bandCenterY(py, h, g.isTopRow());
            int ix = Math.round(cx - 8f), iy = Math.round(cy - 8f);
            ItemStack icon = g.getIcon();
            ir.renderInGuiWithOverrides(icon, ix, iy);
            ir.renderGuiItemOverlay(font, icon, ix, iy);
        }
    }

    /** One glass pill (hotbar selection-pill recipe: sharp refraction, hotbar corner via GlassCorners). */
    private static void s1mp1e$pill(float x0, float y0, float x1, float y1, float opacity, float lift) {
        if (opacity <= 0.004f) return;
        float corner = GlassCorners.knob(x1 - x0, y1 - y0);
        GlassRenderer.glass(x0, y0, x1, y1, 6f, corner, lift, opacity, GlassRenderer.FROST_NONE);
    }

    private static ItemGroup s1mp1e$group(int index) {
        if (index < 0 || index >= ItemGroup.GROUPS.length) return null;
        return ItemGroup.GROUPS[index];
    }

    private ItemGroup s1mp1e$hovered(double mx, double my, int px, int py, int w, int h) {
        for (ItemGroup g : ItemGroup.GROUPS) {
            if (g != null && hit(g, mx, my, px, py, w, h)) return g;
        }
        return null;
    }
}
