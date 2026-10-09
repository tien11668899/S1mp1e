package dev.s1mp1e.client.module;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.s1mp1e.client.hud.HudFade;
import dev.s1mp1e.glass.render.GlassCorners;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.GuiFlush;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Matrix4f;
import net.minecraft.util.math.Vector4f;

/**
 * Tiny rounded-pill background for text HUD modules, drawn ENTIRELY through
 * {@link DrawableHelper#fill(MatrixStack, int, int, int, int, int)} so it honours the
 * module's current {@link MatrixStack} translate/scale exactly like the text on top of
 * it (unlike {@code GlassRenderer}, whose raw-GL RenderSystem model-view would not follow
 * the matrix and would misalign the two). Three non-overlapping fills give clean rounded
 * corners with no alpha double-blend at the overlaps.
 *
 * <p>1.18.2 has no {@code DrawContext}: fills take a leading {@link MatrixStack} and the
 * immediate-buffer flush goes through {@link GuiFlush#flush()} (vanilla entity consumers plus
 * ImmediatelyFast's HUD batch when that mod is batching).
 */
public final class HudGlass {

    private HudGlass() {}

    /**
     * REAL refractive liquid glass for a HUD element's background — the SAME quality as the hotbar /
     * Keystrokes caps (drawn through {@link GlassRenderer} under the RenderSystem model-view), rather than
     * the flat {@link #pill} fill. Coordinates are ABSOLUTE screen pixels: in the HUD pass the RS
     * model-view and the passed matrix are both identity, so a caller draws its glass here at absolute
     * coords and its text/icons via the matrix at the SAME coords and the two line up (this is exactly
     * the Keystrokes recipe). We flush the buffered HUD draws first so the raw-GL glass lands ON TOP of
     * whatever was buffered before it, and grabNow() is NOT used — the frame-primary world backdrop grabbed
     * at {@code InGameHud.render} HEAD is what a HUD pill should refract. Falls back to the flat rounded pill
     * when the glass pipeline is unavailable, so a HUD element never vanishes.
     *
     * @param alpha panel opacity 0..1
     */
    public static void glassBox(MatrixStack matrices, int x0, int y0, int x1, int y1, float alpha) {
        if (x1 <= x0 || y1 <= y0) return;
        alpha *= HudFade.alpha;   // HUD-module appear/disappear (1 outside a module's draw)
        if (GlassProgram.ensureReady() && GlassProgram.usable()) {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (!SceneCapture.hasBackdrop()) SceneCapture.grabNow();
            // flush buffered HUD draws so the glass sits on top of them
            GuiFlush.flush();
            RenderSystem.disableDepthTest();
            GlassProgram.setShadowScale(mc.currentScreen != null ? 0f : 1f);
            GlassRenderer.glass(x0, y0, x1, y1, 6f, 0.9f, 0f, alpha, GlassRenderer.FROST_PANEL);
            RenderSystem.enableDepthTest();
        } else {
            int a = Math.max(0, Math.min(255, Math.round(alpha * 0x88)));
            roundFill(matrices, x0, y0, x1 - x0, y1 - y0, 3, (a << 24) | 0x101014);
        }
    }

    /**
     * REAL refractive liquid glass at ABSOLUTE scaled-GUI coords, with the HUD hotbar corner radius (user rule R2 —
     * every NEW glass piece gets the hotbar corner via {@link GlassCorners}). Same housekeeping as {@link #glassBox}
     * (flush the buffered HUD draws, world backdrop, drop-shadow suppressed while a screen dims the background), but
     * the corner is the hotbar radius in GUI px rather than the fixed 0.9 the existing HUD pills use — so the BATCH-B
     * HUD overlays (chat / tab list / toast / action bar) match the effect strip and creative tab pills, not the older
     * pills, without touching those. Falls back to a flat rounded rect so a surface never vanishes.
     */
    public static void glassBoxHotbar(float x0, float y0, float x1, float y1, float alpha) {
        if (x1 <= x0 || y1 <= y0) return;
        float a = alpha < 0f ? 0f : (alpha > 1f ? 1f : alpha);
        if (GlassProgram.ensureReady() && GlassProgram.usable()) {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (!SceneCapture.hasBackdrop()) SceneCapture.grabNow();
            GuiFlush.flush();
            RenderSystem.disableDepthTest();
            GlassProgram.setShadowScale(mc.currentScreen != null ? 0f : 1f);
            GlassRenderer.glass(x0, y0, x1, y1, 6f, GlassCorners.knob(x1 - x0, y1 - y0), 0f, a, GlassRenderer.FROST_PANEL);
            GlassProgram.setShadowScale(1f);
            RenderSystem.enableDepthTest();
        } else {
            int fa = Math.max(0, Math.min(255, Math.round(a * 0x88)));
            GlassRenderer.roundRect(x0, y0, x1, y1, GlassCorners.radiusPx(x1 - x0, y1 - y0), (fa << 24) | 0x101014);
        }
    }

    /**
     * As {@link #glassBoxHotbar} but the rect is given in the CURRENT {@link MatrixStack}'s LOCAL space (a vanilla HUD
     * element that vanilla draws under a translate/scale — chat, action bar, toast). The corners are projected to
     * absolute screen-GUI px through the pose so {@link GlassRenderer} (which draws under the RS model-view at the GUI
     * base, not the passed matrix) lands exactly where vanilla's own {@code fill}/{@code drawTexture} at the same local
     * coords would. Only translate + uniform scale are used by these HUD elements (no rotation), so the axis-aligned
     * corner projection is exact.
     */
    public static void glassBoxLocalHotbar(MatrixStack matrices, float lx0, float ly0, float lx1, float ly1, float alpha) {
        Matrix4f m = matrices.peek().getPositionMatrix();
        Vector4f p0 = new Vector4f(lx0, ly0, 0f, 1f); p0.transform(m);
        Vector4f p1 = new Vector4f(lx1, ly1, 0f, 1f); p1.transform(m);
        glassBoxHotbar(Math.min(p0.getX(), p1.getX()), Math.min(p0.getY(), p1.getY()),
                       Math.max(p0.getX(), p1.getX()), Math.max(p0.getY(), p1.getY()), alpha);
    }

    /**
     * A TRUE-capsule refracting glass surface (fully round semicircle ends) at absolute coords, via the LENS program
     * ({@code glass_lens.fsh}, {@code CORNER_FRAC = 1.0}) — the regular glass program caps the corner at a quarter of
     * the short side and cannot make a true capsule, so the boss-bar glass (G3) uses this. {@code frost} 0.5 = frosted,
     * {@code opacity} 0..1. Falls back to the rounded {@link #glassBoxHotbar} then a flat pill.
     */
    public static void capsule(float x0, float y0, float x1, float y1, float opacity, float frost) {
        if (x1 <= x0 || y1 <= y0) return;
        float a = opacity < 0f ? 0f : (opacity > 1f ? 1f : opacity);
        if (GlassProgram.ensureReady() && GlassProgram.lensUsable()) {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (!SceneCapture.hasBackdrop()) SceneCapture.grabNow();
            GuiFlush.flush();
            RenderSystem.disableDepthTest();
            GlassProgram.setShadowScale(mc.currentScreen != null ? 0f : 1f);
            GlassRenderer.lens(x0, y0, x1, y1, 1.0f, 0f, a, frost);
            GlassProgram.setShadowScale(1f);
            RenderSystem.enableDepthTest();
        } else if (GlassProgram.ensureReady() && GlassProgram.usable()) {
            glassBoxHotbar(x0, y0, x1, y1, a);
        } else {
            int fa = Math.max(0, Math.min(255, Math.round(a * 0x88)));
            GlassRenderer.roundRect(x0, y0, x1, y1, Math.min(x1 - x0, y1 - y0) * 0.5f, (fa << 24) | 0x101014);
        }
    }

    /**
     * A solid/translucent COLOURED rounded-or-capsule fill (ROUND program: true AA SDF corners, no backdrop, never
     * flickers) at absolute coords — the boss-bar's blue health capsule. {@code radiusPx} clamps to a full capsule.
     * Buffered HUD draws are flushed first so this raw-GL fill lands on top of them.
     */
    public static void colorCapsule(float x0, float y0, float x1, float y1, float radiusPx, int argb) {
        if (x1 <= x0 || y1 <= y0) return;
        GuiFlush.flush();
        RenderSystem.disableDepthTest();
        GlassRenderer.roundRect(x0, y0, x1, y1, radiusPx, argb);
        RenderSystem.enableDepthTest();
    }

    // ---- matrix-local variants (the names the 1.20.1 / 1.21.1 lines use: shared code is written against them) ----

    /** Matrix-local rect -> absolute screen px {x0, y0, x1, y1} through the top position matrix (axis-aligned). */
    public static float[] absRect(MatrixStack matrices, float lx0, float ly0, float lx1, float ly1) {
        Matrix4f m = matrices.peek().getPositionMatrix();
        Vector4f p0 = new Vector4f(lx0, ly0, 0f, 1f); p0.transform(m);
        Vector4f p1 = new Vector4f(lx1, ly1, 0f, 1f); p1.transform(m);
        return new float[] { Math.min(p0.getX(), p1.getX()), Math.min(p0.getY(), p1.getY()),
                             Math.max(p0.getX(), p1.getX()), Math.max(p0.getY(), p1.getY()) };
    }

    /** Uniform scale baked into the matrix (for scaling a local px radius to screen px). */
    public static float matrixScale(MatrixStack matrices) {
        Matrix4f m = matrices.peek().getPositionMatrix();
        Vector4f o = new Vector4f(0f, 0f, 0f, 1f); o.transform(m);
        Vector4f u = new Vector4f(1f, 0f, 0f, 1f); u.transform(m);
        return Math.abs(u.getX() - o.getX());
    }

    /**
     * Solid/translucent COLOURED rounded rect (AA SDF, no backdrop -> never flickers) given in the CURRENT matrix's
     * local space: the typing caret, the boss-bar fill, readability scrims. {@code radiusLocal} is in the caller's
     * local px (scaled to screen px by the matrix scale). Drawn immediately, depth test off. Falls back to a rounded
     * {@link DrawableHelper#fill}.
     */
    public static void roundFillCtx(MatrixStack matrices, float lx0, float ly0, float lx1, float ly1,
                                    float radiusLocal, int argb) {
        if (lx1 <= lx0 || ly1 <= ly0) return;
        if (GlassProgram.ensureReady() && GlassProgram.roundUsable()) {
            float[] r = absRect(matrices, lx0, ly0, lx1, ly1);
            float rad = radiusLocal * matrixScale(matrices);
            GuiFlush.flush();
            RenderSystem.disableDepthTest();
            GlassRenderer.roundRect(r[0], r[1], r[2], r[3], rad, HudFade.argb(argb));   // HUD-module fade
            RenderSystem.enableDepthTest();
        } else {
            roundFill(matrices, Math.round(lx0), Math.round(ly0), Math.round(lx1 - lx0), Math.round(ly1 - ly0),
                      Math.round(radiusLocal), argb);   // roundFill applies the fade itself
        }
    }

    /** {@link #capsule} for a rect given in the current matrix's local space. */
    public static void capsuleCtx(MatrixStack matrices, float lx0, float ly0, float lx1, float ly1,
                                  float opacity, float frost) {
        opacity *= HudFade.alpha;   // HUD-module appear/disappear (1 outside a module's draw)
        float[] r = absRect(matrices, lx0, ly0, lx1, ly1);
        capsule(r[0], r[1], r[2], r[3], opacity, frost);
    }

    /** {@link #glassBoxLocalHotbar} under the name the newer lines use for a matrix-local glass panel. */
    public static void glassBoxCtx(MatrixStack matrices, float lx0, float ly0, float lx1, float ly1, float alpha) {
        glassBoxLocalHotbar(matrices, lx0, ly0, lx1, ly1, alpha * HudFade.alpha);   // HUD-module appear/disappear
    }

    /** Rounded translucent pill from (x,y) size (w,h) in the current matrix space. */
    public static void pill(MatrixStack matrices, int x, int y, int w, int h, int argb) {
        if (w <= 0 || h <= 0) return;
        argb = HudFade.argb(argb);   // HUD-module appear/disappear (1 outside a module's draw)
        int r = Math.min(3, Math.min(w, h) / 2);
        if (r <= 0) { DrawableHelper.fill(matrices, x, y, x + w, y + h, argb); return; }
        DrawableHelper.fill(matrices, x + r,     y,        x + w - r, y + h,     argb);  // centre band, full height
        DrawableHelper.fill(matrices, x,         y + r,    x + r,     y + h - r, argb);  // left band
        DrawableHelper.fill(matrices, x + w - r, y + r,    x + w,     y + h - r, argb);  // right band
    }

    /**
     * A properly ROUNDED rectangle (quarter-circle corners of radius {@code r}) drawn entirely through
     * {@link DrawableHelper#fill(MatrixStack, int, int, int, int, int)}, so it honours the current matrix
     * (unlike {@code GlassRenderer}). The centre band is one fill; each of the {@code r} corner rows is
     * inset by the circle profile.
     */
    public static void roundFill(MatrixStack matrices, int x, int y, int w, int h, int r, int argb) {
        if (w <= 0 || h <= 0) return;
        argb = HudFade.argb(argb);   // HUD-module appear/disappear (1 outside a module's draw)
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
        if (r == 0) { DrawableHelper.fill(matrices, x, y, x + w, y + h, argb); return; }
        DrawableHelper.fill(matrices, x, y + r, x + w, y + h - r, argb);   // centre band (full width)
        for (int i = 0; i < r; i++) {
            double dy = r - i - 0.5;                    // vertical distance from corner centre to this row
            int inset = (int) Math.round(r - Math.sqrt(Math.max(0.0, r * r - dy * dy)));
            DrawableHelper.fill(matrices, x + inset, y + i,         x + w - inset, y + i + 1,   argb);   // top row
            DrawableHelper.fill(matrices, x + inset, y + h - 1 - i, x + w - inset, y + h - i,   argb);   // bottom row
        }
    }

    /** Linear-interpolate two ARGB colours (t in [0,1]); interpolates the alpha channel too. */
    public static int lerpArgb(int c0, int c1, float t) {
        if (t <= 0f) return c0;
        if (t >= 1f) return c1;
        int a = lerp((c0 >>> 24) & 255, (c1 >>> 24) & 255, t);
        int r = lerp((c0 >> 16) & 255, (c1 >> 16) & 255, t);
        int g = lerp((c0 >> 8) & 255, (c1 >> 8) & 255, t);
        int b = lerp(c0 & 255, c1 & 255, t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int lerp(int a, int b, float t) { return a + Math.round((b - a) * t); }
}
