package dev.s1mp1e.client.module;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import org.joml.Matrix4f;

/**
 * Tiny rounded-pill background for text HUD modules, drawn ENTIRELY through
 * {@link DrawContext#fill} so it honours the module's {@code ctx.getMatrices()}
 * translate/scale exactly like the text on top of it (unlike {@code GlassRenderer},
 * whose raw-GL RenderSystem model-view would not follow the ctx matrix and would
 * misalign the two). Three non-overlapping fills give clean rounded corners with no
 * alpha double-blend at the overlaps.
 */
public final class HudGlass {

    private HudGlass() {}

    /**
     * REAL refractive liquid glass for a HUD element's background — the SAME quality as the hotbar /
     * Keystrokes caps (drawn through {@link GlassRenderer} under the RenderSystem model-view), rather than
     * the flat {@link #pill} fill. Coordinates are ABSOLUTE screen pixels: in the HUD pass the RS
     * model-view and the {@code ctx} matrix are both identity, so a caller draws its glass here at absolute
     * coords and its text/icons via the ctx matrix at the SAME coords and the two line up (this is exactly
     * the Keystrokes recipe). We flush the DrawContext first so the raw-GL glass lands ON TOP of whatever
     * was buffered before it, and grabNow() is NOT used — the frame-primary world backdrop grabbed at
     * {@code InGameHud.render} HEAD is what a HUD pill should refract. Falls back to the flat rounded pill
     * when the glass pipeline is unavailable, so a HUD element never vanishes.
     *
     * @param alpha panel opacity 0..1
     */
    public static void glassBox(DrawContext ctx, int x0, int y0, int x1, int y1, float alpha) {
        if (x1 <= x0 || y1 <= y0) return;
        if (GlassProgram.ensureReady() && GlassProgram.usable()) {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (!SceneCapture.hasBackdrop()) SceneCapture.grabNow();
            ctx.draw();                       // flush buffered HUD draws so the glass sits on top of them
            RenderSystem.disableDepthTest();
            GlassProgram.setShadowScale(mc.currentScreen != null ? 0f : 1f);
            GlassRenderer.glass(x0, y0, x1, y1, 6f, 0.9f, 0f, alpha, GlassRenderer.FROST_PANEL);
            RenderSystem.enableDepthTest();
        } else {
            int a = Math.max(0, Math.min(255, Math.round(alpha * 0x88)));
            roundFill(ctx, x0, y0, x1 - x0, y1 - y0, 3, (a << 24) | 0x101014);
        }
    }

    // ---- ctx-matrix-baking HUD glass (G: chat / tablist / boss bar / toasts / action bar) --------
    //
    // These G overlays are enqueued INSIDE a translated/scaled DrawContext matrix (chat scales by the
    // chat scale; the toast manager translates for the slide-in; the action bar translates to centre).
    // GlassRenderer draws under the RenderSystem model-view (identity in the HUD pass), NOT the ctx
    // matrix, so a raw-GL glass quad at the caller's LOCAL coords would land at the wrong place. Each
    // helper bakes the ctx matrix (a pure 2-D scale+translate here — no rotation) into ABSOLUTE screen
    // px and draws there, so the glass lines up with the vanilla text/items that DO follow the ctx
    // matrix. The backdrop is the world grab taken at InGameHud.render HEAD (never grabbed again mid-HUD
    // -> no self-sampling, no flicker, R4), and ctx.draw() flushes buffered draws so the raw-GL glass
    // sits above them and the later-flushed text sits above the glass.

    /** Ctx-local rect -> absolute screen px {x0,y0,x1,y1} through the top position matrix (axis-aligned). */
    private static float[] absRect(DrawContext ctx, float lx0, float ly0, float lx1, float ly1) {
        Matrix4f m = ctx.getMatrices().peek().getPositionMatrix();
        float sx = m.m00(), sy = m.m11(), tx = m.m30(), ty = m.m31();
        float ax0 = sx * lx0 + tx, ax1 = sx * lx1 + tx;
        float ay0 = sy * ly0 + ty, ay1 = sy * ly1 + ty;
        return new float[] { Math.min(ax0, ax1), Math.min(ay0, ay1), Math.max(ax0, ax1), Math.max(ay0, ay1) };
    }

    /** Uniform scale baked into the ctx matrix (for scaling a local px radius to screen px). */
    private static float ctxScale(DrawContext ctx) {
        return Math.abs(ctx.getMatrices().peek().getPositionMatrix().m00());
    }

    /** Frosted refracting panel for a HUD overlay drawn inside a ctx transform; see the note above. */
    public static void glassBoxCtx(DrawContext ctx, float lx0, float ly0, float lx1, float ly1, float alpha) {
        if (lx1 <= lx0 || ly1 <= ly0) return;
        if (GlassProgram.ensureReady() && GlassProgram.usable()) {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (!SceneCapture.hasBackdrop()) SceneCapture.grabNow();
            float[] r = absRect(ctx, lx0, ly0, lx1, ly1);
            ctx.draw();
            RenderSystem.disableDepthTest();
            GlassProgram.setShadowScale(mc.currentScreen != null ? 0f : 1f);
            GlassRenderer.glass(r[0], r[1], r[2], r[3], 6f, 0.9f, 0f, alpha, GlassRenderer.FROST_PANEL);
            RenderSystem.enableDepthTest();
        } else {
            int a = Math.max(0, Math.min(255, Math.round(alpha * 0x88)));
            roundFill(ctx, Math.round(lx0), Math.round(ly0), Math.round(lx1 - lx0), Math.round(ly1 - ly0),
                      3, (a << 24) | 0x101014);
        }
    }

    /**
     * TRUE-capsule liquid glass (semicircle ends) for a HUD overlay — the boss-bar glass under-layer
     * (G3). Uses the LENS program (glass with a full-capsule corner, corner = 1.0), so the ends are
     * real semicircles the normal glass program cannot reach (it caps the corner at a quarter of the
     * short side). Falls back to a rounded translucent pill.
     */
    public static void capsuleCtx(DrawContext ctx, float lx0, float ly0, float lx1, float ly1,
                                  float opacity, float frost) {
        if (lx1 <= lx0 || ly1 <= ly0) return;
        if (GlassProgram.ensureReady() && GlassProgram.lensUsable()) {
            if (!SceneCapture.hasBackdrop()) SceneCapture.grabNow();
            float[] r = absRect(ctx, lx0, ly0, lx1, ly1);
            ctx.draw();
            RenderSystem.disableDepthTest();
            GlassRenderer.lens(r[0], r[1], r[2], r[3], 1.0f, 0f, opacity, frost);
            RenderSystem.enableDepthTest();
        } else {
            int a = Math.max(0, Math.min(255, Math.round(opacity * 0x99)));
            int h = Math.round(ly1 - ly0);
            roundFill(ctx, Math.round(lx0), Math.round(ly0), Math.round(lx1 - lx0), h,
                      Math.max(1, h / 2), (a << 24) | 0x0B2A4A);
        }
    }

    /**
     * Solid/translucent COLOURED rounded rect (AA SDF, no backdrop -> never flickers) baked through the
     * ctx matrix: the boss-bar blue capsule fill (G3, radius = h/2 -> round ends) and the chat/tablist/
     * toast/action-bar readability scrims. {@code radiusLocal} is in the caller's local px (scaled to
     * screen px by the ctx scale). Falls back to a rounded {@link DrawContext#fill}.
     */
    public static void roundFillCtx(DrawContext ctx, float lx0, float ly0, float lx1, float ly1,
                                    float radiusLocal, int argb) {
        if (lx1 <= lx0 || ly1 <= ly0) return;
        if (GlassProgram.ensureReady() && GlassProgram.roundUsable()) {
            float[] r = absRect(ctx, lx0, ly0, lx1, ly1);
            float rad = radiusLocal * ctxScale(ctx);
            ctx.draw();
            RenderSystem.disableDepthTest();
            GlassRenderer.roundRect(r[0], r[1], r[2], r[3], rad, argb);
            RenderSystem.enableDepthTest();
        } else {
            roundFill(ctx, Math.round(lx0), Math.round(ly0), Math.round(lx1 - lx0), Math.round(ly1 - ly0),
                      Math.round(radiusLocal), argb);
        }
    }

    /** Rounded translucent pill from (x,y) size (w,h) in the current ctx matrix space. */
    public static void pill(DrawContext ctx, int x, int y, int w, int h, int argb) {
        if (w <= 0 || h <= 0) return;
        int r = Math.min(3, Math.min(w, h) / 2);
        if (r <= 0) { ctx.fill(x, y, x + w, y + h, argb); return; }
        ctx.fill(x + r,     y,        x + w - r, y + h,     argb);  // centre band, full height
        ctx.fill(x,         y + r,    x + r,     y + h - r, argb);  // left band
        ctx.fill(x + w - r, y + r,    x + w,     y + h - r, argb);  // right band
    }

    /**
     * A properly ROUNDED rectangle (quarter-circle corners of radius {@code r}) drawn entirely through
     * {@link DrawContext#fill}, so it honours the current ctx matrix (unlike {@code GlassRenderer}).
     * The centre band is one fill; each of the {@code r} corner rows is inset by the circle profile.
     */
    public static void roundFill(DrawContext ctx, int x, int y, int w, int h, int r, int argb) {
        if (w <= 0 || h <= 0) return;
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
        if (r == 0) { ctx.fill(x, y, x + w, y + h, argb); return; }
        ctx.fill(x, y + r, x + w, y + h - r, argb);   // centre band (full width)
        for (int i = 0; i < r; i++) {
            double dy = r - i - 0.5;                    // vertical distance from corner centre to this row
            int inset = (int) Math.round(r - Math.sqrt(Math.max(0.0, r * r - dy * dy)));
            ctx.fill(x + inset, y + i,         x + w - inset, y + i + 1,   argb);   // top row
            ctx.fill(x + inset, y + h - 1 - i, x + w - inset, y + h - i,   argb);   // bottom row
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
