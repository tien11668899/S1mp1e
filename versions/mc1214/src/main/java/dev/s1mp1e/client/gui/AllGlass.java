package dev.s1mp1e.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.s1mp1e.client.module.HudGlass;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import net.minecraft.client.gui.DrawContext;
import org.joml.Matrix4f;

/**
 * 1.21.1 primitives for the all-glass pass (see docs/ALLGLASS_PORT_SPEC.md). The glass/round/button programs draw
 * immediately in raw GL and ignore the DrawContext matrix, so every call here flushes the buffered draws first
 * ({@code ctx.draw()}), bakes the context's translate/scale into absolute screen px, and draws with depth test off —
 * the recipe {@link HudGlass#roundFillCtx} uses — so the result layers and lines up exactly like the vanilla draw it
 * replaces, even inside translated/scaled overlays.
 */
public final class AllGlass {

    private AllGlass() {}

    /** Flat AA rounded fill (readability scrim / tinted bar). */
    public static void scrim(DrawContext ctx, float x0, float y0, float x1, float y1, float radius, int argb) {
        if (((argb >>> 24) & 0xFF) == 0) return;
        HudGlass.roundFillCtx(ctx, x0, y0, x1, y1, radius, argb);
    }

    /** Text field / text area frame: frosted, brighter while focused. */
    public static void field(DrawContext ctx, float x0, float y0, float x1, float y1, boolean focused) {
        scrim(ctx, x0, y0, x1, y1, 4.0f, focused ? 0x4DFFFFFF : 0x2EFFFFFF);
    }

    /** Glass-button material capsule (corner 0..1 of the half-height; 1 = true capsule). */
    public static void capsule(DrawContext ctx, float x0, float y0, float x1, float y1, float corner, float lift, float alpha) {
        if (x1 <= x0 || y1 <= y0 || alpha <= 0.004f) return;
        if (!(GlassProgram.ensureReady() && GlassProgram.btnUsable())) {
            int a = Math.round(Math.max(0f, Math.min(1f, alpha * (0.12f + 0.18f * lift))) * 255f);
            scrim(ctx, x0, y0, x1, y1, (y1 - y0) / 2f * corner, a << 24 | 0xFFFFFF);
            return;
        }
        float[] r = abs(ctx, x0, y0, x1, y1);
        ctx.draw();
        RenderSystem.disableDepthTest();
        GlassRenderer.button(r[0], r[1], r[2], r[3], corner, lift, alpha, true);
        RenderSystem.enableDepthTest();
    }

    /** Refracting glass plate (container-panel material) with an optional grey scrim on top for readable text. */
    public static void plate(DrawContext ctx, float x0, float y0, float x1, float y1, float alpha, int scrimArgb) {
        HudGlass.glassBoxCtx(ctx, x0, y0, x1, y1, alpha);
        if (scrimArgb != 0) scrim(ctx, x0, y0, x1, y1, Math.min(6f, Math.min(x1 - x0, y1 - y0) / 4f), scrimArgb);
    }

    /** Corner fraction that gives a {@code w x h} button-material rect the hotbar radius. */
    public static float hotbarCorner(float w, float h) {
        float half = Math.max(1f, Math.min(w, h) / 2f);
        return Math.min(1f, (22.0f * 1.15f / 4.0f) / half);
    }

    private static float[] abs(DrawContext ctx, float lx0, float ly0, float lx1, float ly1) {
        Matrix4f m = ctx.getMatrices().peek().getPositionMatrix();
        float sx = m.m00(), sy = m.m11(), tx = m.m30(), ty = m.m31();
        float ax0 = sx * lx0 + tx, ax1 = sx * lx1 + tx, ay0 = sy * ly0 + ty, ay1 = sy * ly1 + ty;
        return new float[] { Math.min(ax0, ax1), Math.min(ay0, ay1), Math.max(ax0, ax1), Math.max(ay0, ay1) };
    }
}
