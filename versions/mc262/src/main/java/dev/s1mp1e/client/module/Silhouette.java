package dev.s1mp1e.client.module;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.Mth;

/**
 * Shared "hug the icon's REAL silhouette" outline, used by both {@link ArmorHudModule} and
 * {@link PotionHudModule}. Reads a sprite's CPU-side alpha mask, traces the 1px-OUTSIDE contour ordered
 * CLOCKWISE from the top, and draws it as a coloured outline that clings to the icon's actual curve (like a
 * text outline follows a glyph) with a flowing ripple.
 *
 * <p>26.2 port: mc1211 needed a {@code SpriteContentsAccessor} mixin to reach the private {@code NativeImage}.
 * 26.2's {@link SpriteContents} exposes {@code isTransparent(frame, x, y)} publicly (it reads the retained
 * {@code originalImage}, which lives until the atlas is reloaded), so no accessor mixin is needed. That method
 * treats only alpha == 0 as transparent, so the ink threshold is effectively "alpha &gt; 0" instead of
 * mc1211's "alpha &gt; 32" — identical for vanilla icons, whose alpha is binary.
 *
 * <p>The contour is computed on a 16&times;16 grid (the icon is drawn 16&times;16), so points range over
 * {@code -1..16}. Any read failure (image closed / atlas quirk) yields an empty contour — callers then
 * draw just the icon, never crash.
 */
public final class Silhouette {

    private Silhouette() {}

    /** Alpha above this (0..255) counts as opaque ink (kept for parity; 26.2 reads the mask via isTransparent). */
    public static final int ALPHA_MIN = 32;

    /** The 16&times;16 opaque mask of {@code sprite} (frame 0), OR-ed into {@code op}. Throws on read failure. */
    public static void mask(TextureAtlasSprite sprite, boolean[][] op) {
        SpriteContents contents = sprite.contents();
        int iw = contents.width(), ih = contents.height();
        if (iw <= 0 || ih <= 0) throw new IllegalStateException("empty sprite");
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int nx = Math.min(iw - 1, x * iw / 16), ny = Math.min(ih - 1, y * ih / 16);
                if (!contents.isTransparent(0, nx, ny)) op[x][y] = true;
            }
        }
    }

    /** The 1px-outside contour of {@code sprite}'s silhouette on a 16&times;16 grid, clockwise from top; empty on failure. */
    public static int[] trace(TextureAtlasSprite sprite) {
        try {
            boolean[][] op = new boolean[16][16];
            mask(sprite, op);
            return traceMask(op);
        } catch (Throwable t) {
            dev.s1mp1e.client.ErrorOnce.report("Silhouette trace", t);
            return new int[0];
        }
    }

    /** The 1px-outside contour of an already-built 16&times;16 opaque mask, clockwise from top. */
    public static int[] traceMask(boolean[][] op) {
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
        list.sort((p, q) -> Float.compare(ang(p), ang(q)));   // clockwise from top
        int[] out = new int[list.size() * 2];
        for (int i = 0; i < list.size(); i++) { out[i * 2] = list.get(i)[0]; out[i * 2 + 1] = list.get(i)[1]; }
        return out;
    }

    /**
     * Draw the outline with its origin at {@code (ix,iy)} (the icon's top-left), under the current pose.
     * {@code ratio} in {@code [0,1]} keeps that leading clockwise fraction fully opaque; the remainder fades to
     * a faint 55/255 (ArmorHUD uses it for remaining durability; pass {@code 1f} for a full outline). The base
     * colour flows a bright ripple around the contour.
     */
    public static void draw(GuiGraphicsExtractor g, int[] pts, int ix, int iy, float ratio, int baseRgb, float time) {
        int cnt = pts.length / 2;
        if (cnt == 0) return;
        int keep = Math.round(Mth.clamp(ratio, 0f, 1f) * cnt);
        for (int i = 0; i < cnt; i++) {
            int ex = pts[i * 2], ey = pts[i * 2 + 1];
            float ripple = 0.55f + 0.45f * (float) Math.sin(Math.PI * 2 * ((float) i / cnt * 2f - time));
            int a = i < keep ? 255 : 55;
            int col = (a << 24) | scaleRgb(baseRgb, ripple);
            g.fill(ix + ex, iy + ey, ix + ex + 1, iy + ey + 1, col);
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
