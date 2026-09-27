package dev.s1mp1e.glass.render;

import dev.s1mp1e.glass.anim.Fade;
import dev.s1mp1e.glass.anim.Spring;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Feature (B): the creative tab rows fused into the body glass as ONE sheet, with a hotbar-style selection pill that
 * SLIDES within a row and CROSS-FADES across rows, and a fainter hover pill on the container slot-hover motion. 1.17.1
 * ({@code MatrixStack}, core profile) port of 26.2's {@code GlassTabs}, adapted to this version's tab layout: two rows of
 * SIX columns (vanilla 1.17.1 places tabs {@code 0..4} left-aligned and the "special" column 5 — search / inventory —
 * right-aligned, 12 groups total), no vanilla pagination.
 *
 * <h3>Fused sheet</h3>
 * The body panel ({@code px,py .. px+w,py+h}) is extended by {@link #BAND} px above and below into one glass rect that
 * both tab rows sit inside — no seam, no separate tiles. The sheet keeps the body panel's ABSOLUTE corner radius (the
 * caller rescales the knob for the taller sheet; R2 leaves the existing body radius unchanged). The NEW pieces (the
 * pills) use the HUD hotbar radius via {@link GlassCorners}.
 *
 * <h3>Cells</h3>
 * Each band is divided into {@link #COLUMNS} equal, touching cells; each tab icon is centred in its cell (equal
 * left/right and top/bottom gaps). {@link #hitRel} uses the same cell geometry (in the panel-RELATIVE coordinates that
 * vanilla's {@code isClickInTab} receives from {@code mouseClicked}/{@code mouseReleased}), so what is drawn is what is
 * clicked, and {@link #hoveredGroup} (absolute) drives both the hover pill and the tab tooltip.
 *
 * <h3>Motion (26.2 constants)</h3>
 * Selected pill = two springs, lead ω55 / trail ω30, ζ1 (the hotbar selection-pill rig), sliding along a row; a row
 * switch cross-fades (the old pill fades out 150 ms as a ghost, the new pill snaps to its cell and fades in 100 ms).
 * Hover pill = the same two-axis rig on x AND y with a 100 ms fade-in / 150 ms fade-out, gliding while still visible,
 * else appearing in place. {@link Spring#advance} integrates at fixed 1/120 s substeps. A new creative screen instance
 * owns a new {@code GlassTabs} (a {@code @Unique} field), so all motion snaps on open.
 *
 * <h3>Visibility</h3>
 * Only groups whose {@code renderTabIcon} ran this frame are laid out ({@link #record}); the creative mixin runs after
 * Fabric API's item-group paging injector (priority), so hidden pages never draw or hit.
 */
public final class GlassTabs {

    /** Row depth outside the panel edge (GUI px). Vanilla's top tab sprite protrudes 28 px above the panel. */
    public static final int BAND = 32;   // #8 ≈ cell width (195/6=32.5): square cells → equal top/bottom/left/right spacing
    /** Tabs per row in 1.17.1 (columns 0..5). */
    public static final int COLUMNS = 6;
    private static final float PILL_INSET = 2f;
    private static final float ICON = 16f;
    private static final float PAD = GlassRenderer.PAD_PANEL;          // 26.2 pill pad (AA + edge-shadow bleed) = 12
    /** 26.2 lift knobs 0xE0 (selected, the hotbar pill lift) / 0xF4 (hover, fainter) as neutral-lift fractions. */
    private static final float LIFT_SELECTED = 1f - 0xE0 / 255f;       // 0.1216
    private static final float LIFT_HOVER    = 1f - 0xF4 / 255f;       // 0.0431

    // Groups drawn this frame (visible page), recorded by the creative mixin's renderTabIcon interception.
    private final List<ItemGroup> visible = new ArrayList<ItemGroup>();

    // Selection pill (hotbar rig).
    private final Spring selLead  = new Spring(0f, Spring.OMEGA_SNAP, Spring.DAMPING);
    private final Spring selTrail = new Spring(0f, Spring.OMEGA_MED, Spring.DAMPING);
    private final Fade selFade = new Fade(1f, 100f);   // new-pill fade-in on a row switch
    private int selRow = -1;                            // current pill row (0 top, 1 bottom)
    private boolean selReady;
    // Cross-fade ghost (the pill leaving the old row).
    private float ghostCx, ghostCy;
    private final Fade ghostFade = new Fade(0f, 150f);

    // Hover pill (two-axis slot-hover rig).
    private final Spring hovX1 = new Spring(0f, Spring.OMEGA_SNAP, Spring.DAMPING);
    private final Spring hovX2 = new Spring(0f, Spring.OMEGA_MED, Spring.DAMPING);
    private final Spring hovY1 = new Spring(0f, Spring.OMEGA_SNAP, Spring.DAMPING);
    private final Spring hovY2 = new Spring(0f, Spring.OMEGA_MED, Spring.DAMPING);
    private boolean hovActive, hovReady;
    private final Fade hovFade = new Fade(0f, 150f);

    private long lastNanos;

    // ---- per-frame visibility --------------------------------------------

    /** Start a frame: forget last frame's visible groups (drawBackground HEAD). */
    public void begin() { visible.clear(); }

    /** A tab whose vanilla sprite+icon draw was intercepted this frame (i.e. visible on the current page). */
    public void record(ItemGroup g) {
        if (g != null && !visible.contains(g) && visible.size() < 64) visible.add(g);
    }

    /** True if {@code g} was drawn (visible on the current page) last/this frame. */
    public boolean isVisible(ItemGroup g) { return visible.contains(g); }

    // ---- geometry (GUI px) -------------------------------------------------

    public static float cellW(int w) { return w / (float) COLUMNS; }
    private static float cellCenterX(int px, int w, int col) { return px + (col + 0.5f) * cellW(w); }
    private static float bandCenterY(int py, int h, boolean topRow) {
        return topRow ? py - BAND / 2f : py + h + BAND / 2f;
    }

    /** True if the panel-RELATIVE point {@code (rx,ry)} is inside {@code group}'s fused cell. */
    public static boolean hitRel(ItemGroup group, double rx, double ry, int w, int h) {
        float cw = cellW(w);
        float x0 = group.getColumn() * cw, x1 = x0 + cw;
        float y0 = group.isTopRow() ? -BAND : h;
        float y1 = y0 + BAND;
        return rx >= x0 && rx < x1 && ry >= y0 && ry < y1;
    }

    /** The visible group whose cell contains the ABSOLUTE point, or null. */
    public ItemGroup hoveredGroup(double mx, double my, int px, int py, int w, int h) {
        for (ItemGroup g : visible) {
            if (hitRel(g, mx - px, my - py, w, h)) return g;
        }
        return null;
    }

    // ---- render ------------------------------------------------------------

    /**
     * Draw the fused sheet, the sliding / cross-fading selection pill, the hover pill and every visible tab icon centred
     * in its cell. The caller (creative glass mixin) has already refreshed the backdrop (grabNow, R4) and computed the
     * open fade; {@code sheetCornerKnob} is the body corner rescaled for the taller sheet.
     */
    public void render(int px, int py, int w, int h, float fade, float sheetCornerKnob,
                       ItemGroup selected, double mouseX, double mouseY, ItemRenderer ir, TextRenderer font) {
        long now = System.nanoTime();
        float dt = (lastNanos == 0L) ? (1f / 60f) : Math.min(0.1f, (now - lastNanos) * 1e-9f);
        lastNanos = now;

        // 1) fused sheet — one glass rect covering both bands + the body, body corner radius preserved.
        GlassRenderer.glass(px, py - BAND, px + w, py + h + BAND, GlassRenderer.PAD_PANEL, sheetCornerKnob, 0f, fade,
                GlassRenderer.FROST_PANEL);

        // S9P6 — the selected/hover pill is a SQUARE centred on the tab icon: side = min(cell width, band
        // depth) - 2*inset, so width == height (the icon is centred in both the cell and the band). The slide
        // stretches it horizontally as before; only the resting half-extents become equal.
        float half = (Math.min(cellW(w), (float) BAND) - 2f * PILL_INSET) / 2f;
        float halfW = half;
        float halfH = half;

        // 2) selection pill: hotbar slide within a row, cross-fade across rows.
        if (selected != null) {
            float cx = cellCenterX(px, w, selected.getColumn());
            int row = selected.isTopRow() ? 0 : 1;
            if (!selReady) {
                selLead.snap(cx); selTrail.snap(cx); selRow = row; selReady = true; selFade.snap(1f);
            } else if (row != selRow) {
                // leave a ghost at the old pill, snap the pill to the new cell and fade it in
                ghostCx = (selLead.value() + selTrail.value()) / 2f;
                ghostCy = bandCenterY(py, h, selRow == 0);
                ghostFade.snap(selFade.value());
                ghostFade.to(0f, 150f);
                selLead.snap(cx); selTrail.snap(cx); selRow = row;
                selFade.snap(0f); selFade.to(1f, 100f);
            } else {
                selLead.setTarget(cx); selTrail.setTarget(cx);
            }
            selLead.advance(dt); selTrail.advance(dt);
        }

        // 3) hover pill — only over a visible, non-selected cell.
        ItemGroup hov = hoveredGroup(mouseX, mouseY, px, py, w, h);
        boolean hovering = hov != null && (selected == null || hov.getIndex() != selected.getIndex());
        if (hovering) {
            float cx = cellCenterX(px, w, hov.getColumn());
            float cy = bandCenterY(py, h, hov.isTopRow());
            if (!hovReady || (!hovActive && hovFade.value() <= 0.05f)) {
                hovX1.snap(cx); hovX2.snap(cx); hovY1.snap(cy); hovY2.snap(cy); hovReady = true;
            } else {
                hovX1.setTarget(cx); hovX2.setTarget(cx); hovY1.setTarget(cy); hovY2.setTarget(cy);
            }
            hovActive = true;
            hovFade.to(1f, 100f);
        } else {
            hovActive = false;
            hovFade.to(0f, 150f);
        }
        if (hovReady) { hovX1.advance(dt); hovX2.advance(dt); hovY1.advance(dt); hovY2.advance(dt); }

        // pills: ghost (old row) under the hover pill under the live selection pill
        if (ghostFade.isVisible()) {
            pill(ghostCx - halfW, ghostCy - halfH, ghostCx + halfW, ghostCy + halfH, LIFT_SELECTED,
                    fade * ghostFade.value());
        }
        if (hovReady && hovFade.isVisible()) {
            float lox = Math.min(hovX1.value(), hovX2.value()), hix = Math.max(hovX1.value(), hovX2.value());
            float loy = Math.min(hovY1.value(), hovY2.value()), hiy = Math.max(hovY1.value(), hovY2.value());
            pill(lox - halfW, loy - halfH, hix + halfW, hiy + halfH, LIFT_HOVER, fade * hovFade.value());
        }
        if (selected != null && selReady) {
            float lo = Math.min(selLead.value(), selTrail.value());
            float hi = Math.max(selLead.value(), selTrail.value());
            float cy = bandCenterY(py, h, selRow == 0);
            pill(lo - halfW, cy - halfH, hi + halfW, cy + halfH, LIFT_SELECTED, fade * selFade.value());
        }

        // 4) every visible tab icon centred in its cell, on top of the sheet + pills (vanilla renderTabIcon z 100).
        List<ItemGroup> icons = new ArrayList<ItemGroup>(visible);
        if (selected != null && !icons.contains(selected)) icons.add(selected);
        float savedZ = ir.zOffset;
        ir.zOffset = 100f;
        try {
            for (ItemGroup g : icons) {
                float cx = cellCenterX(px, w, g.getColumn());
                float cy = bandCenterY(py, h, g.isTopRow());
                int ix = Math.round(cx - ICON / 2f), iy = Math.round(cy - ICON / 2f);
                ItemStack icon = g.getIcon();
                ir.renderInGuiWithOverrides(icon, ix, iy);
                ir.renderGuiItemOverlay(font, icon, ix, iy);
            }
        } finally {
            ir.zOffset = savedZ;
        }
    }

    /** One glass pill (the hotbar selection-pill recipe: sharp refraction, hotbar corner via GlassCorners). */
    private static void pill(float x0, float y0, float x1, float y1, float lift, float opacity) {
        if (opacity <= 0.004f || x1 <= x0 || y1 <= y0) return;
        GlassRenderer.glass(x0, y0, x1, y1, PAD, GlassCorners.knob(x1 - x0, y1 - y0), lift, opacity,
                GlassRenderer.FROST_NONE);
    }

    // ---- dev probes (DevShotVerify only) ------------------------------------

    /** Selected-pill centre x this frame (lead/trail midpoint), NaN before the first frame. */
    public float probeSelCx() { return selReady ? (selLead.value() + selTrail.value()) / 2f : Float.NaN; }
    public float probeSelFade() { return selFade.value(); }
    public float probeGhostFade() { return ghostFade.value(); }
    public float probeHoverFade() { return hovFade.value(); }
    public float probeHoverCx() { return hovReady ? (hovX1.value() + hovX2.value()) / 2f : Float.NaN; }
}
