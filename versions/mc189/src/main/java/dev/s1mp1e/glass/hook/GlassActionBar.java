package dev.s1mp1e.glass.hook;

import dev.s1mp1e.client.module.HudGlass;
import net.minecraft.client.gui.FontRenderer;

/**
 * Feature G5 — the action bar / overlay message. {@code S1mp1eTransformer} redirects the single
 * {@code fontrenderer.drawString(recordPlaying, x, -4, colour)} call inside
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

    /** Redirect target for {@code FontRenderer.drawString(String,int,int,int)}. Returns the text's advance. */
    public static int draw(FontRenderer fr, String text, int x, int y, int argb) {
        if (fr == null || text == null || text.isEmpty()) {
            return fr == null ? x : fr.drawString(text, x, y, argb);
        }
        try {
            int alphaByte = (argb >>> 24) & 0xFF;
            float alphaFactor = alphaByte / 255f;
            int w = fr.getStringWidth(text);
            HudGlass.glassBox(x - 5, y - 2, x + w + 5, y + 11, 0.85f * alphaFactor);
        } catch (Throwable ignored) {
            // pill failed -> still draw the text below (no regression to the message)
        }
        return fr.drawString(text, x, y, argb);
    }
}
