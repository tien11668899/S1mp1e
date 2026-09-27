package dev.s1mp1e.client.module;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.s1mp1e.client.gui.GlassWidgets;
import dev.s1mp1e.glass.render.GlassCorners;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.MinecraftClient;

/**
 * Rounded-pill / real-glass background for text HUD modules (FPS, coordinates, inventory peek).
 *
 * <p>Ported from mc1211's {@code HudGlass}, but immediate-mode like mc189: 1.15.2 has no
 * {@code DrawContext} buffer to flush, and the HUD pass is already bound to the scaled-GUI
 * projection, so a caller draws its glass here at absolute coords and its text/icons at the SAME
 * coords and the two line up (the Keystrokes recipe). Falls back to a flat rounded fill through
 * {@link GlassWidgets#fillRound} when the glass pipeline is unavailable, so a HUD element never
 * vanishes.
 */
public final class HudGlass {

    private HudGlass() {}

    /**
     * REAL refractive liquid glass for a HUD element's background — the SAME quality as the hotbar /
     * Keystrokes caps (drawn through {@link GlassRenderer}, refracting the frame-primary world backdrop
     * grabbed at {@code InGameHud.render} HEAD). Coordinates are ABSOLUTE scaled-GUI pixels.
     *
     * <p>If no backdrop has been grabbed yet (first frame / a screen-open path), grab one now so the
     * pill still refracts rather than falling back. The drop shadow is suppressed while a screen darkens
     * the scene (like the hotbar), then restored immediately — {@code shadowScale} is PERSISTENT and the
     * HUD pass runs before the screen draws, so leaving it at 0 would strip the shadow off a panel drawn
     * right after.
     *
     * @param alpha panel opacity 0..1
     */
    public static void glassBox(int x0, int y0, int x1, int y1, float alpha) {
        if (x1 <= x0 || y1 <= y0) return;
        if (GlassProgram.ensureReady() && GlassProgram.usable()) {
            if (!SceneCapture.hasBackdrop()) SceneCapture.grabNow();
            MinecraftClient mc = MinecraftClient.getInstance();
            GlassProgram.setShadowScale(mc.currentScreen != null ? 0f : 1f);
            GlassRenderer.glass(x0, y0, x1, y1, 6f, 0.9f, 0f, alpha, GlassRenderer.FROST_PANEL);
            GlassProgram.setShadowScale(1f);
        } else {
            int a = Math.max(0, Math.min(255, Math.round(alpha * 0x88)));
            GlassWidgets.fillRound(x0, y0, x1, y1, (a << 24) | 0x101014, 3f);
        }
    }

    /**
     * REAL refractive liquid glass drawn AT THE GIVEN COORDS, with the HUD hotbar corner radius (R2 — every
     * NEW glass piece gets the hotbar corner via {@link GlassCorners}). The coords are NOT projected through any
     * pose; {@link GlassRenderer} streams the quad under whatever GL model-view is active. On 1.15.2 the vanilla
     * HUD overlays apply their own transform to the GL model-view via {@code RenderSystem.translatef/scaled}
     * (chat's {@code translate(2,8)+scale(chatScale)}, the action bar's {@code translate(w/2,h-68)}, each toast's
     * slide {@code translate(...)}), so a caller passing LOCAL coords lands exactly on the vanilla text/fills that
     * ride the same GL model-view — while a caller in the un-transformed HUD (tab list, boss bar) passes ABSOLUTE
     * coords. Either way this matches the vanilla {@code DrawableHelper.fill(...)} the caller draws for its scrim
     * (that immediate fill rides the SAME GL model-view). The frame-primary world backdrop grabbed at
     * {@code InGameHud.render} HEAD is reused (never {@code grabNow} when a backdrop exists — R4 is satisfied by
     * that single per-frame world grab that precedes all HUD glass). Falls back to a flat rounded rect so a
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
                GlassRenderer.glass(x0, y0, x1, y1, 6f, GlassCorners.cornerKnob(x1 - x0, y1 - y0), 0f, a,
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

    /**
     * A rounded rectangle from {@code (x,y)} size {@code (w,h)} — the fallback key-cap / pill shape for
     * modules (e.g. Keystrokes) when the glass pipeline is unavailable. Delegates to
     * {@link GlassWidgets#fillRound}.
     */
    public static void roundFill(int x, int y, int w, int h, int r, int argb) {
        if (w <= 0 || h <= 0) return;
        GlassWidgets.fillRound(x, y, x + w, y + h, argb, r);
    }

    /** Linear-interpolate two ARGB colours (t in [0,1]); delegates to {@link GlassWidgets#lerpArgb}. */
    public static int lerpArgb(int c0, int c1, float t) {
        return GlassWidgets.lerpArgb(c0, c1, t);
    }
}
