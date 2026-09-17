package dev.s1mp1e.client.hud;

import com.seagull.liquidglass.client.mixin.GuiGraphicsExtractorAccessor;
import com.seagull.liquidglass.client.render.GlassPainter;
import com.seagull.liquidglass.client.render.GlassPipeline;
import com.seagull.liquidglass.client.render.GlassRectRenderState;
import com.seagull.liquidglass.client.render.RoundRectRenderState;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.state.gui.GuiRenderState;

/**
 * 26.2 port of mc1211's {@code HudGlass}: REAL refractive liquid glass behind a HUD element.
 *
 * <p>mc1211 drew glass immediately (flush the DrawContext, raw GL, depth off, shadow scale). 26.2 is deferred,
 * so all of that housekeeping is gone: we just enqueue a {@link GlassRectRenderState} into the frame's
 * {@code GuiRenderState} — byte-for-byte the recipe the recovered {@code HudHotbarMixin} uses for its item-name
 * pill — and {@code GuiRenderer.render()} draws it after the backdrop grab. Text a module draws afterwards with
 * {@code g.text} lands on top of it.
 *
 * <p>The {@code color} argument of {@link GlassRectRenderState} is not a tint: it carries the same four knobs
 * as mc1211's glass shader — A = frost, R = corner-radius scale, G = 1 − lift, B = opacity. {@code 0x80E6FF00}
 * is frost 0.5 (panel), corner 0.9, no lift; the low byte is the element opacity.
 *
 * <p>When the glass pipeline isn't usable (first frame, unsupported device) it falls back to a flat rounded
 * capsule so a HUD element never vanishes.
 */
public final class HudGlass {

    private HudGlass() {}

    /** Frosted-panel knobs: frost 0.5 (A=0x80), corner 0.9 (R=0xE6), no lift (G=0xFF); B = opacity. */
    private static final int PANEL_KNOBS = 0x80E6FF00;
    /** Shadow/AA padding around the rect, in GUI px (the item-name pill uses 10). */
    private static final int PAD = 10;

    /**
     * Glass background at ABSOLUTE scaled-GUI coords, under the current pose.
     *
     * @param alpha panel opacity 0..1
     */
    public static void glassBox(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, float alpha) {
        if (x1 <= x0 || y1 <= y0) return;
        float a = alpha < 0f ? 0f : (alpha > 1f ? 1f : alpha);
        if (GlassPipeline.ensureReady() && GlassPipeline.usable()) {
            int ab = Math.round(a * 255f) & 0xFF;
            GuiRenderState rs = ((GuiGraphicsExtractorAccessor) g).liquidglass$guiRenderState();
            TextureSetup ts = TextureSetup.singleTexture(GlassPipeline.backdropView(), GlassPipeline.sampler());
            rs.addGuiElement(new GlassRectRenderState(GlassPipeline.glass(), ts, g.pose(),
                    x0, y0, x1, y1, PAD, PANEL_KNOBS | ab, null));
        } else {
            int fa = Math.max(0, Math.min(255, Math.round(a * 0x88)));
            GlassPainter.capsule(g, x0, y0, x1 - x0, y1 - y0, 3f, (fa << 24) | 0x101014);
        }
    }

    /**
     * Flat coloured rounded rect with true anti-aliased corners (the 26.2 port of 1.21.1's
     * {@code GlassRenderer.roundRect}) — smooth curves at any GUI scale. Coordinates are absolute scaled-GUI px under
     * the current pose; {@code radiusPx} is clamped to a full capsule. Falls back to the stepped {@link #roundFill}
     * when the round pipeline isn't available.
     */
    public static void roundRect(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, float radiusPx, int argb) {
        if (x1 <= x0 || y1 <= y0) return;
        if (GlassPipeline.ensureReady() && GlassPipeline.roundUsable()) {
            GuiRenderState rs = ((GuiGraphicsExtractorAccessor) g).liquidglass$guiRenderState();
            rs.addGuiElement(new RoundRectRenderState(GlassPipeline.round(), g.pose(), x0, y0, x1, y1, radiusPx, argb, null));
        } else {
            roundFill(g, x0, y0, x1 - x0, y1 - y0, Math.round(radiusPx), argb);
        }
    }

    /** Rounded translucent pill from (x,y) size (w,h) under the current pose (three non-overlapping fills). */
    public static void pill(GuiGraphicsExtractor g, int x, int y, int w, int h, int argb) {
        if (w <= 0 || h <= 0) return;
        int r = Math.min(3, Math.min(w, h) / 2);
        if (r <= 0) { g.fill(x, y, x + w, y + h, argb); return; }
        g.fill(x + r,     y,     x + w - r, y + h,     argb);   // centre band, full height
        g.fill(x,         y + r, x + r,     y + h - r, argb);   // left band
        g.fill(x + w - r, y + r, x + w,     y + h - r, argb);   // right band
    }

    /**
     * A properly ROUNDED rectangle (quarter-circle corners of radius {@code r}) drawn entirely through
     * {@code g.fill}, so it honours the current pose. The centre band is one fill; each of the {@code r}
     * corner rows is inset by the circle profile.
     */
    public static void roundFill(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int argb) {
        if (w <= 0 || h <= 0) return;
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
        if (r == 0) { g.fill(x, y, x + w, y + h, argb); return; }
        g.fill(x, y + r, x + w, y + h - r, argb);   // centre band (full width)
        for (int i = 0; i < r; i++) {
            double dy = r - i - 0.5;
            int inset = (int) Math.round(r - Math.sqrt(Math.max(0.0, r * r - dy * dy)));
            g.fill(x + inset, y + i,         x + w - inset, y + i + 1, argb);   // top row
            g.fill(x + inset, y + h - 1 - i, x + w - inset, y + h - i, argb);   // bottom row
        }
    }

    /** Linear-interpolate two ARGB colours (t in [0,1]); interpolates the alpha channel too. */
    public static int lerpArgb(int c0, int c1, float t) {
        if (t <= 0f) return c0;
        if (t >= 1f) return c1;
        int a = lerp((c0 >>> 24) & 255, (c1 >>> 24) & 255, t);
        int r = lerp((c0 >> 16) & 255, (c1 >> 16) & 255, t);
        int gg = lerp((c0 >> 8) & 255, (c1 >> 8) & 255, t);
        int b = lerp(c0 & 255, c1 & 255, t);
        return (a << 24) | (r << 16) | (gg << 8) | b;
    }

    private static int lerp(int a, int b, float t) { return a + Math.round((b - a) * t); }
}
