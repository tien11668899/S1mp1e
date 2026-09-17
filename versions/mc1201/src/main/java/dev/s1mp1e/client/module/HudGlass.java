package dev.s1mp1e.client.module;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

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
