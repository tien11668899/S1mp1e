package dev.s1mp1e.glass.hook;

import dev.s1mp1e.client.gui.GlassWidgets;

/**
 * allglass #16 — the sound-subtitle box (its one dark rect) becomes a rounded scrim, keeping vanilla's translucency.
 * The coremod redirects the single {@code Gui.drawRect} inside {@code GuiSubtitleOverlay.renderSubtitles} here. The
 * call sits inside a {@code GlStateManager.translate(...)} so the coordinates are local to the subtitle's anchor —
 * a rounded fill in that local space is exactly what we want.
 */
public final class SubtitleGlassHook {

    private SubtitleGlassHook() {}

    public static void rect(int x1, int y1, int x2, int y2, int color) {
        try {
            GlassWidgets.fillRound(x1, y1, x2, y2, color, 4.0f);   // keep vanilla's alpha, just round it
        } catch (Throwable ignored) {
            GlassWidgets.drawRect(x1, y1, x2, y2, color);
        }
    }
}
