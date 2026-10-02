package dev.s1mp1e.client.module;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.s1mp1e.glass.mixin.SpriteImagesAccessor;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.util.math.Matrix4f;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.opengl.GL11;

/**
 * Shared "hug the icon's REAL silhouette" outline, used by both {@link ArmorHudModule} and
 * {@link PotionHudModule}. Reads a sprite's CPU-side alpha mask (via {@link SpriteImagesAccessor}),
 * traces the 1px-OUTSIDE contour ordered CLOCKWISE from the top, and draws it as a coloured outline
 * that clings to the icon's actual curve (like a text outline follows a glyph) with a flowing ripple.
 *
 * <p>The contour is computed on a 16&times;16 grid (the icon is drawn 16&times;16), so points range over
 * {@code -1..16}. Any read failure (image closed / atlas quirk) yields an empty contour — callers then
 * draw just the icon, never crash.
 *
 * <p><b>1.15.2 draw path.</b> Rather than ~60 separate {@code DrawableHelper.fill} calls per icon (each its
 * own Tessellator flush — costly, and awkward with Sodium), every contour pixel is emitted into ONE
 * {@code POSITION_COLOR} quad draw, each quad wound TL&rarr;BL&rarr;BR&rarr;TR (the GUI cull winding). The
 * vertices are pre-multiplied by the caller's {@link MatrixStack} model matrix, so the outline follows the
 * caller's translate/scale exactly like the icon it hugs — whether the caller scales via the MatrixStack
 * (Potion, drawing sprites) or via the fixed-function model-view (Armor, drawing items). Texture is disabled
 * for the batch and re-enabled after; blend is left ON (never {@code disableBlend}); the RenderSystem
 * colour cache is reset 0&rarr;1 so the icon drawn next is not tinted.
 */
public final class Silhouette {

    private Silhouette() {}

    /** Alpha above this (0..255) counts as opaque ink. */
    public static final int ALPHA_MIN = 32;

    /** The 1px-outside contour of {@code sprite}'s silhouette on a 16&times;16 grid, clockwise from top; empty on failure. */
    public static int[] trace(Sprite sprite) {
        try {
            NativeImage img = ((SpriteImagesAccessor) (Object) sprite).s1mp1e$images()[0];   // mip level 0
            if (img == null) return new int[0];
            int iw = Math.min(sprite.getWidth(), img.getWidth()), ih = Math.min(sprite.getHeight(), img.getHeight());
            if (iw <= 0 || ih <= 0) return new int[0];
            boolean[][] op = new boolean[16][16];
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    int nx = Math.min(iw - 1, x * iw / 16), ny = Math.min(ih - 1, y * ih / 16);
                    int abgr = img.getPixelRgba(nx, ny);   // frame 0 at the origin of mip 0; ABGR, alpha = top byte
                    op[x][y] = ((abgr >>> 24) & 0xFF) > ALPHA_MIN;
                }
            }
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
        } catch (Throwable t) {
            return new int[0];
        }
    }

    /**
     * Draw the outline with its origin at {@code (ix,iy)} (the icon's top-left). {@code ratio} in
     * {@code [0,1]} keeps that leading clockwise fraction fully opaque; the remainder fades to a faint
     * 55/255 (ArmorHUD uses it for remaining durability; pass {@code 1f} for a full outline). The base
     * colour flows a bright ripple around the contour.
     */
    public static void draw(int[] pts, int ix, int iy, float ratio, int baseRgb, float time) {
        int cnt = pts.length / 2;
        if (cnt == 0) return;
        int keep = Math.round(MathHelper.clamp(ratio, 0f, 1f) * cnt);

        RenderSystem.disableTexture();
        RenderSystem.enableBlend();
        RenderSystem.disableAlphaTest();
        RenderSystem.blendFuncSeparate(770, 771, 1, 0);
        Tessellator t = Tessellator.getInstance();
        BufferBuilder bb = t.getBuffer();
        bb.begin(GL11.GL_QUADS, VertexFormats.POSITION_COLOR);
        for (int i = 0; i < cnt; i++) {
            int ex = pts[i * 2], ey = pts[i * 2 + 1];
            float ripple = 0.55f + 0.45f * (float) Math.sin(Math.PI * 2 * ((float) i / cnt * 2f - time));
            int a = i < keep ? 255 : 55;
            int col = scaleRgb(baseRgb, ripple);
            int r = (col >> 16) & 0xFF, g = (col >> 8) & 0xFF, b = col & 0xFF;
            float px = ix + ex, py = iy + ey;
            // one 1px quad, wound TL -> BL -> BR -> TR (GUI cull winding)
            bb.vertex(px,        py,        0f).color(r, g, b, a).next();
            bb.vertex(px,        py + 1f,   0f).color(r, g, b, a).next();
            bb.vertex(px + 1f,   py + 1f,   0f).color(r, g, b, a).next();
            bb.vertex(px + 1f,   py,        0f).color(r, g, b, a).next();
        }
        t.draw();
        RenderSystem.enableAlphaTest();
        RenderSystem.enableTexture();
        // Blend stays ENABLED (MC's baseline). Reset the colour cache so the icon drawn next is untinted.
        RenderSystem.color4f(0f, 0f, 0f, 0f);
        RenderSystem.color4f(1f, 1f, 1f, 1f);
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
