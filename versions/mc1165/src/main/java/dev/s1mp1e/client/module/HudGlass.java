package dev.s1mp1e.client.module;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.s1mp1e.glass.render.GlassCorners;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.GuiFlush;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vector4f;
import net.minecraft.util.math.Matrix4f;

/**
 * Tiny rounded-pill background for text HUD modules. Ported from mc1201, with a
 * {@link MatrixStack} threaded through in place of {@code DrawContext}: the flat
 * {@link #pill}/{@link #roundFill} fills go through {@link DrawableHelper#fill}
 * (which honours the module's {@link MatrixStack} translate/scale, so they stay
 * aligned with the text drawn over them), while the real refractive {@link #glassBox}
 * draws through {@link GlassRenderer} under the fixed-function model-view.
 *
 * <p>On 1.16.5 the draws are immediate (no {@code DrawContext} batching), so there is
 * no {@code ctx.draw()} flush — the raw-GL glass simply lands after whatever was drawn
 * before it, in call order.
 */
public final class HudGlass {

    private HudGlass() {}

    /**
     * REAL refractive liquid glass for a HUD element's background — the SAME quality as the hotbar /
     * Keystrokes caps (drawn through {@link GlassRenderer} under the RenderSystem model-view), rather than
     * the flat {@link #pill} fill. Coordinates are ABSOLUTE screen pixels: in the HUD pass the RS
     * model-view and the {@link MatrixStack} are both identity, so a caller draws its glass here at absolute
     * coords and its text/icons via the MatrixStack at the SAME coords and the two line up (this is exactly
     * the Keystrokes recipe). {@code grabNow()} is NOT used when a backdrop already exists — the frame-primary
     * world backdrop grabbed at {@code InGameHud.render} HEAD is what a HUD pill should refract. Depth test is
     * disabled around the glass and {@code setShadowScale} is restored to 1 in a finally so the model-view /
     * shadow-scale state always balances even if the glass throws. Falls back to the flat rounded pill when
     * the glass pipeline is unavailable, so a HUD element never vanishes.
     *
     * @param alpha panel opacity 0..1
     */
    public static void glassBox(MatrixStack m, int x0, int y0, int x1, int y1, float alpha) {
        if (x1 <= x0 || y1 <= y0) return;
        if (GlassProgram.ensureReady() && GlassProgram.usable()) {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (!SceneCapture.hasBackdrop()) SceneCapture.grabNow();
            RenderSystem.disableDepthTest();
            GlassProgram.setShadowScale(mc.currentScreen != null ? 0f : 1f);
            try {
                GlassRenderer.glass(x0, y0, x1, y1, 6f, 0.9f, 0f, alpha, GlassRenderer.FROST_PANEL);
            } finally {
                GlassProgram.setShadowScale(1f);
                RenderSystem.enableDepthTest();
            }
        } else {
            int a = Math.max(0, Math.min(255, Math.round(alpha * 0x88)));
            roundFill(m, x0, y0, x1 - x0, y1 - y0, 3, (a << 24) | 0x101014);
        }
    }

    /**
     * REAL refractive liquid glass drawn AT THE GIVEN COORDS, with the HUD hotbar corner radius (R2 — every
     * NEW glass piece gets the hotbar corner via {@link GlassCorners}). The coords are NOT projected through
     * the {@link MatrixStack}; instead {@link GlassRenderer} streams the quad under whatever GL model-view is
     * active. On 1.16.5 the vanilla HUD overlays apply their own transform to the GL model-view via
     * {@code RenderSystem.translatef/scaled} (chat's {@code translate(2,8)+scale(chatScale)}, the action bar's
     * {@code translate(w/2,h-68)}, each toast's slide {@code translate(x-w*progress,y*h,800+y)}), so a caller
     * passing LOCAL coords lands exactly on the vanilla text/fills that ride the same GL model-view — while a
     * caller in the un-transformed HUD (tab list, boss bar) passes ABSOLUTE coords. Either way this matches the
     * vanilla {@code DrawableHelper.fill(matrices, ...)} the caller draws for its scrim (that fill composes the
     * MatrixStack model matrix onto the SAME GL model-view). The frame-primary world backdrop grabbed at
     * {@code InGameHud.render} HEAD is reused (never {@code grabNow} when a backdrop exists — R4 is satisfied
     * by that single per-frame world grab that precedes all HUD glass). Falls back to a flat rounded rect so a
     * surface never vanishes.
     */
    public static void glassBoxHotbar(float x0, float y0, float x1, float y1, float alpha) {
        if (x1 <= x0 || y1 <= y0) return;
        float a = alpha < 0f ? 0f : (alpha > 1f ? 1f : alpha);
        if (GlassProgram.ensureReady() && GlassProgram.usable()) {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (!SceneCapture.hasBackdrop()) SceneCapture.grabNow();
            RenderSystem.disableDepthTest();
            GlassProgram.setShadowScale(mc.currentScreen != null ? 0f : 1f);
            try {
                GlassRenderer.glass(x0, y0, x1, y1, 6f, GlassCorners.knob(x1 - x0, y1 - y0), 0f, a,
                                    GlassRenderer.FROST_PANEL);
            } finally {
                GlassProgram.setShadowScale(1f);
                RenderSystem.enableDepthTest();
            }
        } else {
            int fa = Math.max(0, Math.min(255, Math.round(a * 0x88)));
            GlassRenderer.roundRect(x0, y0, x1, y1, GlassCorners.radiusPx(x1 - x0, y1 - y0), (fa << 24) | 0x101014);
        }
    }

    /**
     * A TRUE-capsule refracting glass surface (fully round semicircle ends) at the given coords, via the LENS
     * program ({@code glass_lens.fsh}, full-capsule corner) — the regular glass program caps the corner at a
     * quarter of the short side and cannot make a true capsule, so the boss-bar glass (G3) uses this.
     * {@code frost} 0.5 = frosted, {@code opacity} 0..1. Falls back to the rounded {@link #glassBoxHotbar} then
     * a flat capsule.
     */
    public static void capsule(float x0, float y0, float x1, float y1, float opacity, float frost) {
        if (x1 <= x0 || y1 <= y0) return;
        float a = opacity < 0f ? 0f : (opacity > 1f ? 1f : opacity);
        if (GlassProgram.ensureReady() && GlassProgram.lensUsable()) {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (!SceneCapture.hasBackdrop()) SceneCapture.grabNow();
            RenderSystem.disableDepthTest();
            GlassProgram.setShadowScale(mc.currentScreen != null ? 0f : 1f);
            try {
                GlassRenderer.lens(x0, y0, x1, y1, 1.0f, 0f, a, frost);
            } finally {
                GlassProgram.setShadowScale(1f);
                RenderSystem.enableDepthTest();
            }
        } else if (GlassProgram.ensureReady() && GlassProgram.usable()) {
            glassBoxHotbar(x0, y0, x1, y1, a);
        } else {
            int fa = Math.max(0, Math.min(255, Math.round(a * 0x88)));
            GlassRenderer.roundRect(x0, y0, x1, y1, Math.min(x1 - x0, y1 - y0) * 0.5f, (fa << 24) | 0x101014);
        }
    }

    /**
     * A solid/translucent COLOURED rounded-or-capsule fill (ROUND program: true AA SDF corners, no backdrop,
     * never flickers) at the given coords — the boss-bar's blue health capsule. {@code radiusPx} clamps to a
     * full capsule. Drawn under the current GL model-view like {@link #glassBoxHotbar}.
     */
    public static void colorCapsule(float x0, float y0, float x1, float y1, float radiusPx, int argb) {
        if (x1 <= x0 || y1 <= y0) return;
        RenderSystem.disableDepthTest();
        try {
            GlassRenderer.roundRect(x0, y0, x1, y1, radiusPx, argb);
        } finally {
            RenderSystem.enableDepthTest();
        }
    }

    // ---- matrix-local variants (the names the 1.20.1 / 1.21.1 lines use: shared code is written against them) ----

    /** Matrix-local rect -> absolute screen px {x0, y0, x1, y1} through the top position matrix (axis-aligned). */
    public static float[] absRect(MatrixStack matrices, float lx0, float ly0, float lx1, float ly1) {
        Matrix4f m = matrices.peek().getModel();
        Vector4f p0 = new Vector4f(lx0, ly0, 0f, 1f); p0.transform(m);
        Vector4f p1 = new Vector4f(lx1, ly1, 0f, 1f); p1.transform(m);
        return new float[] { Math.min(p0.getX(), p1.getX()), Math.min(p0.getY(), p1.getY()),
                             Math.max(p0.getX(), p1.getX()), Math.max(p0.getY(), p1.getY()) };
    }

    /** Uniform scale baked into the matrix (for scaling a local px radius to screen px). */
    public static float matrixScale(MatrixStack matrices) {
        Matrix4f m = matrices.peek().getModel();
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
            GlassRenderer.roundRect(r[0], r[1], r[2], r[3], rad, argb);
            RenderSystem.enableDepthTest();
        } else {
            roundFill(matrices, Math.round(lx0), Math.round(ly0), Math.round(lx1 - lx0), Math.round(ly1 - ly0),
                      Math.round(radiusLocal), argb);
        }
    }

    /** {@link #capsule} for a rect given in the current matrix's local space. */
    public static void capsuleCtx(MatrixStack matrices, float lx0, float ly0, float lx1, float ly1,
                                  float opacity, float frost) {
        float[] r = absRect(matrices, lx0, ly0, lx1, ly1);
        capsule(r[0], r[1], r[2], r[3], opacity, frost);
    }

    /** {@link #glassBoxHotbar} for a matrix-local rect, under the name the newer lines use for a matrix-local glass panel. */
    public static void glassBoxCtx(MatrixStack matrices, float lx0, float ly0, float lx1, float ly1, float alpha) {
        float[] r = absRect(matrices, lx0, ly0, lx1, ly1);
        glassBoxHotbar(r[0], r[1], r[2], r[3], alpha);
    }

    /** Rounded translucent pill from (x,y) size (w,h) in the current MatrixStack space. */
    public static void pill(MatrixStack m, int x, int y, int w, int h, int argb) {
        if (w <= 0 || h <= 0) return;
        int r = Math.min(3, Math.min(w, h) / 2);
        if (r <= 0) { DrawableHelper.fill(m, x, y, x + w, y + h, argb); return; }
        DrawableHelper.fill(m, x + r,     y,        x + w - r, y + h,     argb);  // centre band, full height
        DrawableHelper.fill(m, x,         y + r,    x + r,     y + h - r, argb);  // left band
        DrawableHelper.fill(m, x + w - r, y + r,    x + w,     y + h - r, argb);  // right band
    }

    /**
     * A properly ROUNDED rectangle (quarter-circle corners of radius {@code r}) drawn entirely through
     * {@link DrawableHelper#fill}, so it honours the current MatrixStack (unlike {@code GlassRenderer}).
     * The centre band is one fill; each of the {@code r} corner rows is inset by the circle profile.
     */
    public static void roundFill(MatrixStack m, int x, int y, int w, int h, int r, int argb) {
        if (w <= 0 || h <= 0) return;
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
        if (r == 0) { DrawableHelper.fill(m, x, y, x + w, y + h, argb); return; }
        DrawableHelper.fill(m, x, y + r, x + w, y + h - r, argb);   // centre band (full width)
        for (int i = 0; i < r; i++) {
            double dy = r - i - 0.5;                    // vertical distance from corner centre to this row
            int inset = (int) Math.round(r - Math.sqrt(Math.max(0.0, r * r - dy * dy)));
            DrawableHelper.fill(m, x + inset, y + i,         x + w - inset, y + i + 1,   argb);   // top row
            DrawableHelper.fill(m, x + inset, y + h - 1 - i, x + w - inset, y + h - i,   argb);   // bottom row
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
