package dev.s1mp1e.o.glass.hook;

import dev.s1mp1e.o.client.module.HudGlass;
import net.minecraft.client.render.TextRenderer;

/**
 * Feature G5 — the action bar / overlay message. {@code S1mp1eTransformer} redirects the single
 * {@code fontrenderer.draw(recordPlaying, x, -4, colour)} call inside
 * {@code GuiIngameForge.renderRecordOverlay} to {@link #draw}, which paints a glass pill behind the text (matching
 * the hotbar item-name pill) and then draws the text exactly as vanilla would.
 *
 * <p>The pill's opacity carries the same fade as the text (the message timer packs the fade into the colour's
 * alpha byte), so the pill fades in and out with the message. The call runs inside vanilla's
 * {@code translate(width/2, height-68)} matrix, so these local coords land in the right place; the glass refracts
 * the world backdrop grabbed at the overlay-pass head.
 */
public final class GlassActionBar {

    private GlassActionBar() {}

    /** Appear-fade length, matching the glass screen-open fade. */
    private static final long APPEAR_NS = 150_000_000L;
    /** Not drawn for longer than this = hidden, so the next draw is a fresh appearance. */
    private static final long GONE_NS = 250_000_000L;
    private static long lastDrawNs, appearStartNs;

    /**
     * Redirect target for {@code TextRenderer.draw(String,int,int,int)}. Returns the text's advance.
     *
     * <p><b>Appear fade (ported from mc189/mc1122).</b> Vanilla fades the message OUT (via its overlay timer, already
     * baked into the colour's alpha byte) but pops it IN. Fade it IN over 150&nbsp;ms from a hidden&rarr;shown edge,
     * keyed on the GAP since the last draw (NOT on the set-message call: the server re-sends the same message each tick
     * and a countdown changes its text, neither of which may restart the fade or it would flicker; a countdown's text
     * change is a tiny gap, far under {@link #GONE_NS}, so it never re-triggers). Both the pill's alpha and the text
     * colour's alpha are multiplied by the appear factor, so they fade in together. The out-fade is still carried by
     * the vanilla timer in {@code argb}'s alpha byte.
     */
    public static int draw(TextRenderer fr, String text, int x, int y, int argb) {
        if (fr == null || text == null || text.isEmpty()) {
            return fr == null ? x : fr.draw(text, x, y, argb);
        }
        long now = System.nanoTime();
        if (now - lastDrawNs > GONE_NS) appearStartNs = now;   // reappeared after a gap -> start the fade in
        lastDrawNs = now;
        float appear = (now - appearStartNs) / (float) APPEAR_NS;
        appear = appear < 0f ? 0f : (appear > 1f ? 1f : appear);

        int alphaByte = (argb >>> 24) & 0xFF;
        try {
            float alphaFactor = alphaByte / 255f;
            int w = fr.getWidth(text);
            HudGlass.glassBox(x - 5, y - 2, x + w + 5, y + 11, 0.85f * alphaFactor * appear);
        } catch (Throwable ignored) {
            // pill failed -> still draw the text below (no regression to the message)
        }
        int outA = Math.round(alphaByte * appear);
        // TextRenderer reads an alpha byte < 4 as fully opaque, which would snap the text to full opacity at the start
        // of the fade. Draw nothing while fully faded, but keep the advance so layout is unchanged.
        if (outA < 4) return x + fr.getWidth(text);
        return fr.draw(text, x, y, (outA << 24) | (argb & 0xFFFFFF));
    }
}
