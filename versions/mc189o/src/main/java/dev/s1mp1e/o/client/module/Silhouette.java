package dev.s1mp1e.o.client.module;

import java.util.ArrayList;
import java.util.List;

import dev.s1mp1e.o.client.hud.HudFade;
import net.minecraft.client.gui.GuiElement;
import net.minecraft.client.render.texture.TextureAtlasSprite;

/**
 * Shared "hug the icon's REAL silhouette" outline, used by both {@link ArmorHudModule} and
 * {@link PotionHudModule}. Reads an icon's CPU-side alpha mask, traces the 1px-OUTSIDE contour
 * ordered CLOCKWISE from the top, and draws it as a coloured outline that clings to the icon's
 * actual curve (like a text outline follows a glyph) with a flowing ripple.
 *
 * <p>The contour is computed on a 16&times;16 grid (the icon is drawn 16&times;16), so points range
 * over {@code -1..16}. Any read failure yields an empty contour — callers then draw just the icon,
 * never crash.
 *
 * <p>1.8.9 has no Mixin, so the alpha SOURCE is read natively: item icons via
 * {@link TextureAtlasSprite#getFrameTextureData()} (int[] ARGB, high byte = alpha); potion icons via
 * a one-time {@code BufferedImage} tile of {@code textures/gui/container/inventory.png} passed in as
 * a raw ARGB buffer + tile rect (see {@link PotionHudModule}). The trace/draw maths are copied
 * verbatim from mc1211; only the source and the {@code GuiElement.fill} primitive differ.
 */
public final class Silhouette {

    private Silhouette() {}

    /** Alpha above this (0..255) counts as opaque ink. */
    public static final int ALPHA_MIN = 32;

    /** The 1px-outside contour of an item {@code sprite}'s silhouette on a 16&times;16 grid, clockwise
     *  from top; empty on failure (e.g. a sprite whose CPU data was released after GPU upload). */
    public static int[] trace(TextureAtlasSprite sprite) {
        try {
            int[][] frame = sprite.getFrame(0);
            int[] px = frame[0];               // mipmap level 0, ARGB
            int iw = sprite.getWidth(), ih = sprite.getHeight();
            if (iw <= 0 || ih <= 0 || px.length < iw * ih) return new int[0];
            boolean[][] op = new boolean[16][16];
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    int nx = Math.min(iw - 1, x * iw / 16), ny = Math.min(ih - 1, y * ih / 16);
                    op[x][y] = ((px[ny * iw + nx] >>> 24) & 0xFF) > ALPHA_MIN;   // alpha = high byte
                }
            }
            return traceOp(op);
        } catch (Throwable t) {
            return new int[0];
        }
    }

    /** The 1px-outside contour of an ARGB tile (potion icons: a rect inside {@code inventory.png}),
     *  sampled on a 16&times;16 grid, clockwise from top; empty on failure. */
    public static int[] traceTile(int[] argb, int imgW, int tileX, int tileY, int tileW, int tileH) {
        try {
            if (argb == null || imgW <= 0 || tileW <= 0 || tileH <= 0) return new int[0];
            boolean[][] op = new boolean[16][16];
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    int nx = tileX + Math.min(tileW - 1, x * tileW / 16);
                    int ny = tileY + Math.min(tileH - 1, y * tileH / 16);
                    int idx = ny * imgW + nx;
                    op[x][y] = idx >= 0 && idx < argb.length
                            && ((argb[idx] >>> 24) & 0xFF) > ALPHA_MIN;
                }
            }
            return traceOp(op);
        } catch (Throwable t) {
            return new int[0];
        }
    }

    /** Shared contour extraction: the 1px-outside 8-neighbour edge test + clockwise-from-top sort. */
    private static int[] traceOp(boolean[][] op) {
        List<int[]> list = new ArrayList<int[]>();
        for (int y = -1; y <= 16; y++) {
            for (int x = -1; x <= 16; x++) {
                if (op(op, x, y)) continue;
                if (op(op, x - 1, y) || op(op, x + 1, y) || op(op, x, y - 1) || op(op, x, y + 1)
                        || op(op, x - 1, y - 1) || op(op, x + 1, y - 1) || op(op, x - 1, y + 1) || op(op, x + 1, y + 1)) {
                    list.add(new int[] { x, y });
                }
            }
        }
        list.sort(new java.util.Comparator<int[]>() {
            public int compare(int[] p, int[] q) { return Float.compare(ang(p), ang(q)); }
        });   // clockwise from top
        int[] out = new int[list.size() * 2];
        for (int i = 0; i < list.size(); i++) { out[i * 2] = list.get(i)[0]; out[i * 2 + 1] = list.get(i)[1]; }
        return out;
    }

    /**
     * Draw the outline with its origin at {@code (ix,iy)} (the icon's top-left). {@code ratio} in
     * {@code [0,1]} keeps that leading clockwise fraction fully opaque; the remainder fades to a faint
     * 55/255 (ArmorHUD uses it for remaining durability; pass {@code 1f} for a full outline). The base
     * colour flows a bright ripple around the contour.
     *
     * <p>Immediate-mode 1.8.9: cells are painted with {@link GuiElement#drawRect} (which winds front-facing and
     * so survives the GUI-pass cull) instead of mc1211's {@code ctx.fill}.
     */
    public static void draw(int[] pts, int ix, int iy, float ratio, int baseRgb, float time) {
        int cnt = pts.length / 2;
        if (cnt == 0) return;
        float r = Math.max(0f, Math.min(1f, ratio));
        int keep = Math.round(r * cnt);
        for (int i = 0; i < cnt; i++) {
            int ex = pts[i * 2], ey = pts[i * 2 + 1];
            float ripple = 0.55f + 0.45f * (float) Math.sin(Math.PI * 2 * ((float) i / cnt * 2f - time));
            int a = i < keep ? 255 : 55;
            int col = HudFade.argb((a << 24) | scaleRgb(baseRgb, ripple));   // HUD-module appear/disappear
            GuiElement.fill(ix + ex, iy + ey, ix + ex + 1, iy + ey + 1, col);
        }
    }

    /** Fill {@code op[16][16]} with the inventory-sheet tile's opaque mask (same grid sampling as {@link #traceTile}). */
    public static void maskTile(int[] argb, int imgW, int tileX, int tileY, int tileW, int tileH, boolean[][] op) {
        if (argb == null || imgW <= 0 || tileW <= 0 || tileH <= 0) throw new IllegalStateException("no sheet");
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int nx = tileX + Math.min(tileW - 1, x * tileW / 16);
                int ny = tileY + Math.min(tileH - 1, y * tileH / 16);
                int idx = ny * imgW + nx;
                op[x][y] = idx >= 0 && idx < argb.length && ((argb[idx] >>> 24) & 0xFF) > ALPHA_MIN;
            }
        }
    }

    /**
     * Draw the icon's silhouette as a SOLID colour fill with its origin at {@code (ix,iy)} (a filled disc that
     * extends 1px beyond the ink, so the icon on top leaves a clean 1px colour border). Same immediate-mode
     * {@link GuiElement#fill} per-cell path as {@link #draw} — flat colour over the whole mask.
     */
    public static void fill(boolean[][] op, int ix, int iy, int baseRgb) {
        int argb = HudFade.argb(0xFF000000 | (baseRgb & 0xFFFFFF));   // HUD-module appear/disappear (1 outside a module's draw)
        if ((argb >>> 24) == 0) return;
        for (int y = -1; y <= 16; y++) {
            for (int x = -1; x <= 16; x++) {
                if (op(op, x, y)
                        || op(op, x - 1, y) || op(op, x + 1, y) || op(op, x, y - 1) || op(op, x, y + 1)
                        || op(op, x - 1, y - 1) || op(op, x + 1, y - 1) || op(op, x - 1, y + 1) || op(op, x + 1, y + 1)) {
                    GuiElement.fill(ix + x, iy + y, ix + x + 1, iy + y + 1, argb);
                }
            }
        }
    }

    private static boolean op(boolean[][] op, int x, int y) { return x >= 0 && x < 16 && y >= 0 && y < 16 && op[x][y]; }

    private static float ang(int[] p) {
        float a = (float) Math.atan2(p[0] - 7.5f, -(p[1] - 7.5f));   // 0 at top, +clockwise
        return a < 0 ? a + (float) (Math.PI * 2) : a;
    }

    private static int scaleRgb(int rgb, float k) {
        int r = Math.min(255, Math.round(((rgb >> 16) & 0xFF) * k));
        int g = Math.min(255, Math.round(((rgb >> 8) & 0xFF) * k));
        int b = Math.min(255, Math.round((rgb & 0xFF) * k));
        return (r << 16) | (g << 8) | b;
    }
}
