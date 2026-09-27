package dev.s1mp1e.client.module;

import java.util.ArrayList;
import java.util.List;

import dev.s1mp1e.client.gui.GlassWidgets;
import dev.s1mp1e.glass.mixin.SpriteAccessor;
import net.minecraft.client.texture.Sprite;
import net.minecraft.util.math.MathHelper;

/**
 * Shared "hug the icon's REAL silhouette" outline, used by both {@link ArmorHudModule} and
 * {@link PotionHudModule}. Reads an icon's CPU-side alpha mask, traces the 1px-OUTSIDE contour ordered
 * CLOCKWISE from the top, and draws it as a coloured outline that clings to the icon's actual curve
 * (like a text outline follows a glyph) with a flowing ripple.
 *
 * <p>The contour is computed on a 16&times;16 grid (the icon is drawn 16&times;16), so points range over
 * {@code -1..16}. Any read failure yields an empty contour — callers then draw just the icon, never
 * crash.
 *
 * <p>1.13.2 port: the trace/draw maths are mc1211's, copied verbatim. There are TWO alpha sources:
 * <ul>
 *   <li>{@link #trace(Sprite)} — item icons (ArmorHUD), read through the {@link SpriteAccessor} pixel
 *       reader over {@code sprite.getWidth()/getHeight()} scaled to 16&times;16 (the mc1144 path).</li>
 *   <li>{@link #traceTile} — potion icons. 1.13.2 has no status-effect atlas; the icons live on the
 *       shared {@code textures/gui/container/inventory.png} sheet, so PotionHUD passes a one-time ARGB
 *       read of that sheet plus the tile rect (the mc189 path).</li>
 * </ul>
 * The draw primitive is the immediate-mode {@link GlassWidgets#drawRect} (a front-facing quad that
 * survives the GUI-pass cull and leaves blend enabled) instead of mc1211's {@code ctx.fill}.
 */
public final class Silhouette {

    private Silhouette() {}

    /** Alpha above this (0..255) counts as opaque ink. */
    public static final int ALPHA_MIN = 32;

    /** The 1px-outside contour of an item {@code sprite}'s silhouette on a 16&times;16 grid, clockwise
     *  from top; empty on failure. */
    public static int[] trace(Sprite sprite) {
        try {
            if (sprite == null) return new int[0];
            SpriteAccessor acc = (SpriteAccessor) (Object) sprite;
            int iw = sprite.getWidth(), ih = sprite.getHeight();
            if (iw <= 0 || ih <= 0) return new int[0];
            boolean[][] op = new boolean[16][16];
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    int nx = Math.min(iw - 1, x * iw / 16), ny = Math.min(ih - 1, y * ih / 16);
                    int abgr = acc.s1mp1e$framePixel(0, 0, nx, ny);   // frame 0, mip 0; ABGR, alpha = top byte
                    op[x][y] = ((abgr >>> 24) & 0xFF) > ALPHA_MIN;
                }
            }
            return traceOp(op);
        } catch (Throwable t) {
            return new int[0];
        }
    }

    /** The 1px-outside contour of an ARGB tile (potion icons: a rect inside {@code inventory.png}),
     *  sampled on a 16&times;16 grid, clockwise from top; empty on failure. {@code argb} is row-major
     *  ARGB (alpha in the high byte) of an image {@code imgW} pixels wide. */
    public static int[] traceTile(int[] argb, int imgW, int tileX, int tileY, int tileW, int tileH) {
        try {
            if (argb == null || imgW <= 0 || tileW <= 0 || tileH <= 0) return new int[0];
            boolean[][] op = new boolean[16][16];
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    int nx = tileX + Math.min(tileW - 1, x * tileW / 16);
                    int ny = tileY + Math.min(tileH - 1, y * tileH / 16);
                    int idx = ny * imgW + nx;
                    op[x][y] = nx >= 0 && nx < imgW && idx >= 0 && idx < argb.length
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
        // outer edge = a transparent/outside pixel touching an opaque one (1px OUTSIDE the shape)
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
     * <p>Immediate-mode 1.13.2: each 1px cell is a {@link GlassWidgets#drawRect} fill (front-facing
     * TL->BL->BR->TR, so it survives the GUI-pass cull; blend left enabled).
     */
    public static void draw(int[] pts, int ix, int iy, float ratio, int baseRgb, float time) {
        if (pts == null) return;
        int cnt = pts.length / 2;
        if (cnt == 0) return;
        int keep = Math.round(MathHelper.clamp(ratio, 0f, 1f) * cnt);
        for (int i = 0; i < cnt; i++) {
            int ex = pts[i * 2], ey = pts[i * 2 + 1];
            float ripple = 0.55f + 0.45f * (float) Math.sin(Math.PI * 2 * ((float) i / cnt * 2f - time));
            int a = i < keep ? 255 : 55;
            int col = (a << 24) | scaleRgb(baseRgb, ripple);
            GlassWidgets.drawRect(ix + ex, iy + ey, ix + ex + 1, iy + ey + 1, col);
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
