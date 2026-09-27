package dev.s1mp1e.client.module;

import dev.s1mp1e.client.Chroma;
import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.Setting;

/**
 * Chroma HUD: S1mp1e's own HUD text (FPS, CPS, coordinates, keystroke labels) - and optionally the HUD accent
 * colours - cycle through an animated rainbow.
 *
 * <p>This module draws nothing itself; it is a colour source. HUD text goes through
 * {@link dev.s1mp1e.client.gui.HudText}, which asks {@link #textRgbAt} for the colour at each glyph's SCREEN
 * position, and accent code asks {@link #accent}. Hue = time x speed - (screenX + screenY) x wave, so with a
 * non-zero wave the rainbow sweeps diagonally across the whole HUD as one continuous gradient (each character
 * of a string gets its own hue); with wave 0 every element shows the same, uniformly cycling hue.
 *
 * <p>Only colours change: the frosted glass itself stays neutral (its colour int is a set of shader knobs, not a
 * tint), text keeps its own alpha and shadow, and nothing new is shown. Pure recolour, fair-play. Byte-for-byte the
 * 26.2 source (no MC types); OFF by default.
 */
public final class ChromaHudModule extends Module {

    /** One full hue cycle per this many GUI px of (x + y) at wave 1.0. */
    private static final float WAVE_SPAN_PX = 240f;

    public final Setting speed      = add(Setting.number("Speed", 1.0D, 0.1D, 5.0D));
    public final Setting saturation = add(Setting.number("Saturation", 0.75D, 0.0D, 1.0D));
    public final Setting wave       = add(Setting.number("Wave", 0.5D, 0.0D, 1.0D));
    public final Setting accents    = add(Setting.bool("Accents", false));

    private static ChromaHudModule instance;

    public ChromaHudModule() {
        super("ChromaHud", "HUD");
        this.enabled = false;
        instance = this;
    }

    /** True when HUD text should be drawn in chroma. */
    public static boolean textActive() {
        ChromaHudModule m = instance;
        return m != null && m.enabled;
    }

    /** True when the HUD accent colours should follow the chroma too. */
    public static boolean accentsActive() {
        ChromaHudModule m = instance;
        return m != null && m.enabled && m.accents.boolValue;
    }

    /** True when each character should get its own hue (wave > 0); false = one colour per string. */
    public static boolean perCharacter() {
        ChromaHudModule m = instance;
        return m != null && m.wave.doubleValue > 1.0e-3;
    }

    /** Chroma {@code 0xRRGGBB} at a screen position (scaled-GUI px). Callers must check {@link #textActive} first. */
    public static int textRgbAt(float screenX, float screenY) {
        ChromaHudModule m = instance;
        if (m == null) return 0xFFFFFF;
        double offset = -(screenX + screenY) * m.wave.doubleValue / WAVE_SPAN_PX;
        return Chroma.rgb(Chroma.hue(m.speed.doubleValue, offset), (float) m.saturation.doubleValue, 1f);
    }

    /**
     * An accent colour: {@code baseArgb} unchanged unless accents are on, in which case its RGB becomes the chroma
     * hue at that screen position and its alpha is kept.
     */
    public static int accent(int baseArgb, float screenX, float screenY) {
        if (!accentsActive()) return baseArgb;
        return (baseArgb & 0xFF000000) | textRgbAt(screenX, screenY);
    }
}
