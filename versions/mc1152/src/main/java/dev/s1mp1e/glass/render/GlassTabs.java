package dev.s1mp1e.glass.render;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.s1mp1e.client.gui.GlassWidgets;
import dev.s1mp1e.glass.anim.Fade;
import dev.s1mp1e.glass.anim.Spring;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.DiffuseLighting;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;

/**
 * Creative-inventory category tabs as part of the inventory's ONE glass piece (user: category row joined to the bag, no
 * seam between). The 1.15.2 (FF-Fabric, immediate-mode) port of 1.20.1 / 26.2's {@code render/GlassTabs}.
 *
 * <p>The creative body glass is drawn as a sheet extended {@link #BAND} GUI px above and below the panel (see
 * {@code CreativeGlassMixin}), so each tab row is simply the top / bottom band of the same glass sheet — no separate
 * strips, no seam. Inside a band the row is divided into {@link #COLUMNS} equal touching cells (vanilla's 0..6 columns).
 * The selected tab is a lifted glass pill (hotbar look + {@link GlassCorners hotbar corner radius}) that SLIDES along its
 * row and CROSS-FADES when the selection jumps rows; a hovered tab gets a fainter pill with the container slot-hover
 * motion. {@code CreativeGlassMixin} moves vanilla's tab hit boxes ({@code isClickInTab}) to the same cells.
 *
 * <p>Immediate-mode ordering: {@code CreativeGlassMixin} defers every tab (via {@link #deferTile}/{@link #deferIcon})
 * while vanilla walks them, draws the fused body sheet, then calls {@link #flush} once (from the selected tab's redirect)
 * to paint the pills and every icon ON TOP of the body — the same "body -> pills -> icons" order 26.2 uses. Everything
 * runs at pose-identity: {@code drawBackground} is called before {@code render}'s {@code translate(x,y)}, so all coords
 * here are absolute GUI px.
 */
public final class GlassTabs {
    private GlassTabs() {}

    /** Depth of a tab row outside the panel edge, GUI px (vanilla's top tab starts ~28 px above the panel). */
    public static final int BAND = 28;
    /** Vanilla's tab grid per row: 5 left-aligned + 2 right-aligned columns. */
    public static final int COLUMNS = 7;
    /** Vanilla hit-box width of a tab. */
    public static final int TAB_W = 26;
    private static final int ICON = 16;
    private static final int PILL_INSET = 2;
    private static final float FROST = 1.0f;                 // sharp refraction (FROST_NONE)
    private static final int LIFT_SELECTED = 0xE0;          // G byte -> lift 0.125 (the hotbar selected pill's lift)
    private static final int LIFT_HOVER = 0xF4;             // G byte -> lift 0.043 (fainter)
    private static final float PAD = 12f;

    private static final ArrayList<int[]> tiles = new ArrayList<int[]>();     // {column, top(0/1), selected(0/1), hovered(0/1)}
    private static final ArrayList<Object[]> icons = new ArrayList<Object[]>();  // {ItemStack, column, top(0/1)}

    public static void reset() {
        tiles.clear();
        icons.clear();
    }

    public static float cellW(int imageWidth) { return imageWidth / (float) COLUMNS; }

    /** Tab hit-box x relative to leftPos: a {@link #TAB_W}-wide box centred in the tab's cell. */
    public static int tabX(int column, int imageWidth) {
        float c = cellW(imageWidth);
        return Math.round(column * c + (c - TAB_W) / 2f);
    }

    public static int iconX(int leftPos, int column, int imageWidth) {
        float c = cellW(imageWidth);
        return leftPos + Math.round(column * c + (c - ICON) / 2f);
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

    // ---- motion (selected pill: hotbar slide lead 55 / trail 30 within a row, cross-fade across rows;
    //              hover pill: slot-hover motion, fade in 100 / out 150 ms) ----
    private static final float LEAD_W = 55f;
    private static final float TRAIL_W = 30f;
    private static final float STEP = 1f / 120f;
    private static Object owner;
    private static long lastNanos;
    private static Spring selLead, selTrail;
    private static int selCol = -1;
    private static boolean selTop;
    private static final Fade selFade = new Fade(1f, 100f);
    private static float ghostCx;
    private static boolean ghostTop;
    private static final Fade ghostFade = new Fade(0f, 150f);
    private static Spring hx1, hx2, hy1, hy2;
    private static boolean hoverActive;
    private static final Fade hoverFade = new Fade(0f, 100f);

    /** Draw the (animated) selected and hovered pills, then every tab icon, on top of the already-drawn body glass. */
    public static void flush(Object screenOwner, int leftPos, int topPos,
                             int imageWidth, int imageHeight, int fadeByte) {
        try {
            long now = System.nanoTime();
            float dt = lastNanos == 0L ? 1f / 60f : Math.min(0.1f, (now - lastNanos) * 1.0e-9f);
            lastNanos = now;
            if (screenOwner != owner) {
                owner = screenOwner;
                selLead = null;
                hx1 = null;
                hoverActive = false;
                hoverFade.snap(0f);
                ghostFade.snap(0f);
                selFade.snap(1f);
            }
            float c = cellW(imageWidth);
            // S9P6 — the selected/hover pill is a SQUARE centred on the tab icon: side = min(cell width, band
            // depth) - 2*inset, so width == height. The slide still stretches it horizontally between lead/trail.
            float half = (Math.min(c, (float) BAND) - 2f * PILL_INSET) / 2f;
            float hw = half;
            float hh = half;
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
                    selLead = new Spring(cx, LEAD_W, 1f);
                    selTrail = new Spring(cx, TRAIL_W, 1f);
                    selCol = sel[0]; selTop = top; selFade.snap(1f);
                } else if (top != selTop) {
                    ghostCx = (selLead.value() + selTrail.value()) / 2f;
                    ghostTop = selTop;
                    ghostFade.snap(selFade.value());
                    ghostFade.to(0f, 150f);
                    selLead.snap(cx); selTrail.snap(cx);
                    selFade.snap(0f); selFade.to(1f, 100f);
                    selTop = top; selCol = sel[0];
                } else if (sel[0] != selCol) {
                    selLead.setTarget(cx); selTrail.setTarget(cx);
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
                        hx1 = new Spring(cx, LEAD_W, 1f);
                        hx2 = new Spring(cx, TRAIL_W, 1f);
                        hy1 = new Spring(cy, LEAD_W, 1f);
                        hy2 = new Spring(cy, TRAIL_W, 1f);
                    }
                    hoverActive = true;
                }
                hoverFade.to(1f, 100f);
            } else {
                hoverActive = false;
                hoverFade.to(0f, 150f);
            }

            for (float rem = dt; rem > 0f; rem -= STEP) {
                float h = Math.min(rem, STEP);
                if (selLead != null) { selLead.update(h); selTrail.update(h); }
                if (hx1 != null) { hx1.update(h); hx2.update(h); hy1.update(h); hy2.update(h); }
            }

            int fb = fadeByte & 0xFF;
            if (ghostFade.isVisible()) {
                float cy = bandCy(topPos, imageHeight, ghostTop);
                pill(ghostCx - hw, cy - hh, ghostCx + hw, cy + hh, LIFT_SELECTED, Math.round(fb * ghostFade.value()));
            }
            if (hx1 != null && hoverFade.isVisible()) {
                float lo = Math.min(hx1.value(), hx2.value());
                float hi = Math.max(hx1.value(), hx2.value());
                float vlo = Math.min(hy1.value(), hy2.value());
                float vhi = Math.max(hy1.value(), hy2.value());
                pill(lo - hw, vlo - hh, hi + hw, vhi + hh, LIFT_HOVER, Math.round(fb * hoverFade.value()));
            }
            if (sel != null && selLead != null) {
                float lo = Math.min(selLead.value(), selTrail.value());
                float hi = Math.max(selLead.value(), selTrail.value());
                float cy = bandCy(topPos, imageHeight, selTop);
                pill(lo - hw, cy - hh, hi + hw, cy + hh, LIFT_SELECTED, Math.round(fb * selFade.value()));
            }
            drawIcons(leftPos, topPos, imageWidth, imageHeight);
        } finally {
            reset();
        }
    }

    /** Paint every deferred tab icon on top of the pills, with the version's verified GUI-item lighting recipe. */
    private static void drawIcons(int leftPos, int topPos, int imageWidth, int imageHeight) {
        if (icons.isEmpty()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        ItemRenderer ir = mc.getItemRenderer();
        // Sort the icons above the glass body (vanilla renderTabIcon uses blit offset / zOffset 100).
        int savedBlit = 0;
        try { savedBlit = getBlitOffset(); } catch (Throwable ignored) {}
        setBlitOffset(100);
        ir.zOffset = 100f;
        GlassWidgets.resetColorCache();
        DiffuseLighting.enableGuiDepthLighting();
        RenderSystem.enableRescaleNormal();
        try {
            for (Object[] ic : icons) {
                int col = ((Integer) ic[1]).intValue();
                boolean top = ((Integer) ic[2]).intValue() != 0;
                int ix = iconX(leftPos, col, imageWidth);
                int iy = iconY(topPos, imageHeight, top);
                ItemStack st = (ItemStack) ic[0];
                ir.renderGuiItem(st, ix, iy);
                ir.renderGuiItemOverlay(mc.textRenderer, st, ix, iy);
            }
        } finally {
            RenderSystem.disableRescaleNormal();
            ir.zOffset = 0f;
            setBlitOffset(savedBlit);
            // Restore MC's baseline (never leave a GUI path with blend off); defeat the colour cache.
            GlassWidgets.resetColorCache();
            RenderSystem.enableAlphaTest();
            RenderSystem.enableBlend();
        }
    }

    // DrawableHelper.setBlitOffset / getBlitOffset are static on the client's shared cursor; the item renderer reads the
    // same z as the vanilla tab icons through it. Reflect off the screen singleton so no Screen import is needed here.
    private static int getBlitOffset() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.currentScreen == null ? 0 : mc.currentScreen.getBlitOffset();
    }

    private static void setBlitOffset(int z) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.currentScreen != null) mc.currentScreen.setBlitOffset(z);
    }

    private static void retarget(float cx, float cy) {
        hx1.setTarget(cx); hx2.setTarget(cx);
        hy1.setTarget(cy); hy2.setTarget(cy);
    }

    private static float bandCy(int topPos, int imageHeight, boolean top) {
        return top ? topPos - BAND / 2f : topPos + imageHeight + BAND / 2f;
    }

    private static void pill(float fx0, float fy0, float fx1, float fy1, int liftByte, int fadeByte) {
        int x0 = Math.round(fx0), y0 = Math.round(fy0), x1 = Math.round(fx1), y1 = Math.round(fy1);
        if (x1 <= x0 || y1 <= y0 || (fadeByte & 0xFF) == 0) return;
        if (!GlassProgram.usable() || !SceneCapture.hasBackdrop()) return;
        float corner = GlassCorners.cornerKnob(x1 - x0, y1 - y0);   // hotbar corner radius (R2)
        float lift = 1f - (liftByte & 0xFF) / 255f;
        float opacity = (fadeByte & 0xFF) / 255f;
        GlassRenderer.glass(x0, y0, x1, y1, PAD, corner, lift, opacity, FROST);
    }
}
