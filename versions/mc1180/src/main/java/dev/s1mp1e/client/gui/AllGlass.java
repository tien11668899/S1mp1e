package dev.s1mp1e.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.s1mp1e.client.module.HudGlass;
import dev.s1mp1e.glass.render.GlassCorners;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.GuiFlush;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.util.math.MatrixStack;

/**
 * 1.18.2 primitives for the all-glass pass (see docs/ALLGLASS_PORT_SPEC.md) — the MatrixStack port of the 1.20.1 line's
 * {@code AllGlass} (which takes a {@code DrawContext}). 1.18.2 has no {@code DrawContext}: drawing is {@code MatrixStack}
 * + {@link net.minecraft.client.gui.DrawableHelper} statics, and the batched-draw flush the 1.20.1 code did with
 * {@code ctx.draw()} is {@link GuiFlush#flush()} here (vanilla entity consumers + ImmediatelyFast's HUD batch).
 *
 * <p>The glass / round / button programs draw immediately in raw GL and ignore the matrix stack, so every call bakes
 * the stack's translate/scale into absolute screen px ({@link HudGlass#absRect}), flushes the buffered draws, and draws
 * with depth test off — exactly the recipe {@link HudGlass} uses for HUD glass — so the result layers and lines up with
 * the vanilla draw it replaces even inside a translated/scaled overlay. All of this delegates to the already-verified
 * mc1180 {@link HudGlass} MatrixStack primitives so the look matches the rest of the 1.18.2 line.
 */
public final class AllGlass {

    private AllGlass() {}

    /** Flat AA rounded fill (readability scrim / tinted bar), in the current matrix's local space. */
    public static void scrim(MatrixStack m, float x0, float y0, float x1, float y1, float radius, int argb) {
        if (((argb >>> 24) & 0xFF) == 0) return;
        HudGlass.roundFillCtx(m, x0, y0, x1, y1, radius, argb);
    }

    /** Text field / text area frame: frosted, brighter while focused. */
    public static void field(MatrixStack m, float x0, float y0, float x1, float y1, boolean focused) {
        scrim(m, x0, y0, x1, y1, 4.0f, focused ? 0x4DFFFFFF : 0x2EFFFFFF);
    }

    /** Glass-button material capsule (corner 0..1 of the half-height; 1 = true capsule). */
    public static void capsule(MatrixStack m, float x0, float y0, float x1, float y1, float corner, float lift, float alpha) {
        if (x1 <= x0 || y1 <= y0 || alpha <= 0.004f) return;
        if (!(GlassProgram.ensureReady() && GlassProgram.btnUsable())) {
            int a = Math.round(Math.max(0f, Math.min(1f, alpha * (0.12f + 0.18f * lift))) * 255f);
            scrim(m, x0, y0, x1, y1, (y1 - y0) / 2f * corner, a << 24 | 0xFFFFFF);
            return;
        }
        float[] r = HudGlass.absRect(m, x0, y0, x1, y1);
        GuiFlush.flush();
        RenderSystem.disableDepthTest();
        GlassRenderer.button(r[0], r[1], r[2], r[3], corner, lift, alpha, true);
        RenderSystem.enableDepthTest();
    }

    /** Refracting glass plate (container-panel material) with an optional grey scrim on top for readable text. */
    public static void plate(MatrixStack m, float x0, float y0, float x1, float y1, float alpha, int scrimArgb) {
        HudGlass.glassBoxCtx(m, x0, y0, x1, y1, alpha);
        if (scrimArgb != 0) scrim(m, x0, y0, x1, y1, Math.min(6f, Math.min(x1 - x0, y1 - y0) / 4f), scrimArgb);
    }

    /**
     * Large refracting glass pane with the hotbar corner (the rule for new glass surfaces) and a grey scrim of the same
     * radius — for list panes, where {@link #plate}'s HUD corner (0.9 of the half-size) would round a tall pane into a
     * capsule.
     */
    public static void pane(MatrixStack m, float x0, float y0, float x1, float y1, float alpha, int scrimArgb) {
        if (x1 <= x0 || y1 <= y0) return;
        if (GlassProgram.ensureReady() && GlassProgram.usable()) {
            if (!SceneCapture.hasBackdrop()) SceneCapture.grabNow();
            float[] r = HudGlass.absRect(m, x0, y0, x1, y1);
            GuiFlush.flush();
            RenderSystem.disableDepthTest();
            GlassRenderer.glass(r[0], r[1], r[2], r[3], 6f,
                    GlassCorners.hotbarCornerFrac(r[2] - r[0], r[3] - r[1]), 0f, alpha, GlassRenderer.FROST_PANEL);
            RenderSystem.enableDepthTest();
        }
        if (scrimArgb != 0) scrim(m, x0, y0, x1, y1, GlassCorners.HOTBAR_RADIUS, scrimArgb);
    }

    /** Corner fraction that gives a {@code w x h} button-material rect the hotbar radius (BTN program: radius = half*corner). */
    public static float hotbarCorner(float w, float h) {
        float half = Math.max(1f, Math.min(w, h) / 2f);
        return Math.min(1f, GlassCorners.HOTBAR_RADIUS / half);
    }
}
