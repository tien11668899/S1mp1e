package dev.s1mp1e.client.module;

import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.Setting;
import dev.s1mp1e.client.gui.Chroma;

/**
 * H2 — Chroma HUD. Recolours S1mp1e's OWN HUD text (FPS / coords / CPS / keystroke labels) with a
 * moving rainbow; with a wave each character gets its own hue for a diagonal sweep, wave 0 gives one
 * uniform cycling hue. Optionally the HUD accents (the keystroke press highlight) cycle too.
 *
 * <p>Pure recolour of what the client already draws — no new information, no world reading, glass
 * frost stays neutral. OFF by default. The text routing happens in {@link dev.s1mp1e.client.gui.HudText};
 * this module only holds the settings and the shared on/off state.
 */
public final class ChromaHudModule extends Module {

    /** Single live instance, so {@link dev.s1mp1e.client.gui.HudText} and the accent hook read the
     *  settings without a registry lookup every glyph. Set in the constructor. */
    public static ChromaHudModule INSTANCE;

    public final Setting speed   = add(Setting.number("Speed",       1.0D, 0.1D, 5.0D));
    public final Setting sat      = add(Setting.number("Saturation", 0.75D, 0.0D, 1.0D));
    public final Setting wave     = add(Setting.number("Wave",       0.5D, 0.0D, 1.0D));
    public final Setting accents  = add(Setting.bool("Accents", false));

    public ChromaHudModule() {
        super("ChromaHud", "HUD");
        this.enabled = false;
        INSTANCE = this;
    }

    /** True when the rainbow should be applied to HUD text right now. */
    public static boolean active() {
        return INSTANCE != null && INSTANCE.enabled;
    }

    /** {@link dev.s1mp1e.client.hud.HudText} seam: true when HUD text should be drawn in chroma. */
    public static boolean textActive() {
        return INSTANCE != null && INSTANCE.enabled;
    }

    /** True when the HUD accent colours should follow the chroma too (Accents on). */
    public static boolean accentsActive() {
        ChromaHudModule m = INSTANCE;
        return m != null && m.enabled && m.accents.boolValue;
    }

    /** True when each glyph should get its own hue (wave &gt; 0); false = one colour per string. */
    public static boolean perCharacter() {
        ChromaHudModule m = INSTANCE;
        return m != null && m.wave.doubleValue > 1.0e-3;
    }

    /** The chroma {@code 0xRRGGBB} at a screen position (scaled-GUI px) — the {@link dev.s1mp1e.client.hud.HudText}
     *  entry point. Callers must check {@link #textActive()} first. Alias of {@link #rgbAt}. */
    public static int textRgbAt(double screenX, double screenY) {
        return rgbAt(screenX, screenY);
    }

    /**
     * The chroma {@code 0xRRGGBB} at a screen position (no alpha). Caller keeps its own alpha. Uses the
     * live speed / saturation / wave settings.
     */
    public static int rgbAt(double screenX, double screenY) {
        ChromaHudModule m = INSTANCE;
        if (m == null) return 0xFFFFFF;
        float hue = Chroma.hue(m.speed.doubleValue, m.wave.doubleValue, screenX, screenY);
        return Chroma.rgb(hue, (float) m.sat.doubleValue);
    }

    /**
     * Recolour an accent: returns the chroma colour (keeping {@code base}'s alpha) when the module AND
     * the Accents option are both on, otherwise {@code base} unchanged. Used by the keystroke highlight.
     */
    public static int accent(int base, double screenX, double screenY) {
        ChromaHudModule m = INSTANCE;
        if (m == null || !m.enabled || !m.accents.boolValue) return base;
        int a = base & 0xFF000000;
        return a | rgbAt(screenX, screenY);
    }
}
