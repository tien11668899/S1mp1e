package dev.s1mp1e.client.gui;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.s1mp1e.client.module.HudGlass;
import dev.s1mp1e.glass.render.GlassCorners;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.GuiFlush;
import dev.s1mp1e.glass.render.SceneCapture;

/**
 * 1.14.4 primitives for the all-glass pass (see docs/ALLGLASS_PORT_SPEC.md) — the fixed-function port of the 1.15.2
 * line's {@code AllGlass}. 1.14.4 has no {@code MatrixStack} in the GUI: a caller that translates / scales does so on the
 * fixed-function GL model-view, and every glass program here reads {@code gl_ModelViewProjectionMatrix}
 * ({@code glass.vsh}), so a rect is given — and drawn — in the CURRENT GL model-view's local space (no baking, unlike the
 * MatrixStack lines). Each call flushes the buffered draws and draws with depth test off — the {@link HudGlass} recipe —
 * so the result layers and lines up with the vanilla draw it replaces, even inside a translated overlay.
 *
 * <p><b>1.14.4 vs 1.15.2.</b> Byte-for-byte the mc1152 class except {@code com.mojang.blaze3d.systems.RenderSystem}
 * (1.15+) is replaced by {@code com.mojang.blaze3d.platform.GlStateManager} (1.14.4's blaze3d-pre-rewrite GL state
 * facade) for the depth-test toggles — exactly as {@link HudGlass} already does on this line.
 */
public final class AllGlass {

    private AllGlass() {}

    /** Flat AA rounded fill (readability scrim / tinted bar), in the current GL model-view's local space. */
    public static void scrim(float x0, float y0, float x1, float y1, float radius, int argb) {
        if (((argb >>> 24) & 0xFF) == 0) return;
        HudGlass.roundFillCtx(x0, y0, x1, y1, radius, argb);
    }

    /** Text field / text area frame: frosted, brighter while focused. */
    public static void field(float x0, float y0, float x1, float y1, boolean focused) {
        scrim(x0, y0, x1, y1, 4.0f, focused ? 0x4DFFFFFF : 0x2EFFFFFF);
    }

    /** Glass-button material capsule (corner 0..1 of the half-height; 1 = true capsule). */
    public static void capsule(float x0, float y0, float x1, float y1, float corner, float lift, float alpha) {
        if (x1 <= x0 || y1 <= y0 || alpha <= 0.004f) return;
        if (!(GlassProgram.ensureReady() && GlassProgram.btnUsable())) {
            int a = Math.round(Math.max(0f, Math.min(1f, alpha * (0.12f + 0.18f * lift))) * 255f);
            scrim(x0, y0, x1, y1, (y1 - y0) / 2f * corner, a << 24 | 0xFFFFFF);
            return;
        }
        GuiFlush.flush();
        GlStateManager.disableDepthTest();
        GlassRenderer.button(x0, y0, x1, y1, corner, lift, alpha, true);
        GlStateManager.enableDepthTest();
    }

    /** Refracting glass plate (container-panel material) with an optional grey scrim on top for readable text. */
    public static void plate(float x0, float y0, float x1, float y1, float alpha, int scrimArgb) {
        HudGlass.glassBoxCtx(x0, y0, x1, y1, alpha);
        if (scrimArgb != 0) scrim(x0, y0, x1, y1, Math.min(6f, Math.min(x1 - x0, y1 - y0) / 4f), scrimArgb);
    }

    /**
     * Large refracting glass pane with the hotbar corner (the rule for new glass surfaces) and a grey scrim of the same
     * radius — for list panes, where {@link #plate}'s HUD corner (0.9 of the half-size) would round a tall pane into a
     * capsule.
     */
    public static void pane(float x0, float y0, float x1, float y1, float alpha, int scrimArgb) {
        if (x1 <= x0 || y1 <= y0) return;
        if (GlassProgram.ensureReady() && GlassProgram.usable()) {
            if (!SceneCapture.hasBackdrop()) SceneCapture.grabNow();
            GuiFlush.flush();
            GlStateManager.disableDepthTest();
            GlassRenderer.glass(x0, y0, x1, y1, 6f,
                    GlassCorners.hotbarCornerFrac(x1 - x0, y1 - y0), 0f, alpha, GlassRenderer.FROST_PANEL);
            GlStateManager.enableDepthTest();
        }
        if (scrimArgb != 0) scrim(x0, y0, x1, y1, GlassCorners.HOTBAR_RADIUS, scrimArgb);
    }

    /** Corner fraction that gives a {@code w x h} button-material rect the hotbar radius (BTN program: radius = half*corner). */
    public static float hotbarCorner(float w, float h) {
        float half = Math.max(1f, Math.min(w, h) / 2f);
        return Math.min(1f, GlassCorners.HOTBAR_RADIUS / half);
    }

    /**
     * The GL state a replaced {@code DrawableHelper.fill} leaves behind (it ENDS with {@code enableTexture()} +
     * {@code disableBlend()}, javap 1.14.4). Call after drawing glass in place of a vanilla fill so whatever vanilla
     * draws next (text, icons) starts from the state it expects.
     */
    public static void afterFill() {
        GlStateManager.enableTexture();
        GlStateManager.disableBlend();
    }
}
