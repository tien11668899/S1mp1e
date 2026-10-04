package dev.s1mp1e.glass.hook;

import dev.s1mp1e.glass.render.GlassCorners;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;

/**
 * Feature G2 — the player tab list (1.12.2 port of the mc189 hook).
 *
 * <p>{@code S1mp1eTransformer} redirects the {@code drawRect(...)} calls inside
 * {@code GuiPlayerTabOverlay.renderPlayerlist} to {@link #rect}. In 1.12.2 those are: the header
 * background, the player-list panel and the footer background (all black at alpha 0x80,
 * {@code Integer.MIN_VALUE}, tall) plus the per-row name stripe (white at alpha ~0x20, height 8).
 * The tall structural panels become refracting glass plates with a soft grey scrim; the short per-row
 * stripe becomes a thinned scrim so the striping stays gentle over the glass. Ping bars, hearts and
 * names keep their vanilla positions and stay readable (vanilla draws them after these rects).
 *
 * <p>The plates reuse the world backdrop grabbed once at the overlay-pass head, so they never sample
 * already-drawn HUD glass — the clean-frame discipline that keeps them flicker-free (R4). New pieces,
 * so they take the hotbar corner radius via {@link GlassCorners} (R2). Falls back to the vanilla draw
 * when the glass pipeline is down.
 */
public final class GlassTabList {

    private GlassTabList() {}

    /** Rects taller than this are treated as the header/list/footer plates; shorter ones are row stripes. */
    private static final int ROW_MAX_H = 10;
    /** Grey readability scrim over the plates. */
    private static final int SCRIM = 0x66101018;

    private static int scaleA(int argb, float f) {
        return (Math.round(((argb >>> 24) & 0xFF) * f) << 24) | (argb & 0xFFFFFF);
    }

    /** Redirect target for {@code Gui.drawRect(int,int,int,int,int)} inside renderPlayerlist. */
    public static void rect(int x0, int y0, int x1, int y1, int argb) {
        int lx = Math.min(x0, x1), rx = Math.max(x0, x1);
        int ty = Math.min(y0, y1), by = Math.max(y0, y1);
        int w = rx - lx, h = by - ty;
        if (w <= 0 || h <= 0) return;

        Minecraft mc = Minecraft.getMinecraft();
        // group 7: the whole list (plates, stripes, names, heads) fades with the tab key (TabListFade)
        float fa = dev.s1mp1e.glass.render.TabListFade.alpha();
        if (fa <= 0.004f) return;
        boolean glass = GlassProgram.ensureReady() && GlassProgram.usable() && SceneCapture.hasBackdrop();
        boolean round = GlassProgram.ensureReady() && GlassProgram.roundUsable();

        if (h > ROW_MAX_H) {
            // header / list / footer plate
            if (glass) {
                GlassProgram.setShadowScale(mc != null && mc.currentScreen != null ? 0f : 1f);
                try {
                    float knob = GlassCorners.hotbarCorner(w, h);
                    GlassRenderer.glass(lx, ty, rx, by, GlassRenderer.PAD_PANEL, knob, 0f, fa, GlassRenderer.FROST_PANEL);
                } finally {
                    GlassProgram.setShadowScale(1f);
                }
                if (round) {
                    GlassRenderer.roundRect(lx, ty, rx, by, GlassCorners.hotbarRadiusPx(w, h), scaleA(SCRIM, fa));
                }
            } else {
                Gui.drawRect(lx, ty, rx, by, scaleA(0x99101018, fa));   // opaque fallback
            }
        } else {
            // short per-row name stripe -> thinned scrim (half its alpha), gentle striping
            int a = Math.round(((argb >>> 24) & 0xFF) / 2f * fa);
            int scrim = (a << 24) | (argb & 0xFFFFFF);
            if (round) {
                GlassRenderer.roundRect(lx, ty, rx, by, 2f, scrim);
            } else {
                Gui.drawRect(lx, ty, rx, by, scrim);
            }
        }
    }
}
