package dev.s1mp1e.client.module;

import dev.s1mp1e.client.gui.GlassWidgets;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.MinecraftClient;

/**
 * Rounded-pill / real-glass background for text HUD modules (FPS, coordinates, inventory peek).
 *
 * <p>Ported from mc1211's {@code HudGlass}, but immediate-mode like mc189: 1.14.4 has no
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
