package dev.s1mp1e.client.module;

import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.Setting;

/**
 * NameTags — controls the background plate drawn behind entity / player name tags.
 *
 * <p>Three looks, chosen by the {@code Background} setting:
 * <ul>
 *   <li><b>Glass</b> (default) — REAL liquid-glass refraction: the tag is cancelled in world space and redrawn at the
 *       GUI stage as a refractive glass plate (the hotbar's glass pipeline) that bends the terrain behind it, name text
 *       crisp on top. See {@link dev.s1mp1e.client.gui.NameTagGlass}.</li>
 *   <li><b>Vanilla</b> — the plate is left exactly as the game draws it (translucent black).</li>
 *   <li><b>Off</b> — the plate's alpha is zeroed, so only the name text shows, no background.</li>
 * </ul>
 *
 * <p><b>FAIR PLAY.</b> This only restyles, moves or removes the plate the game already draws. It reads nothing about the
 * entity, changes no visibility / see-through-walls rule, and never adds a plate where vanilla draws none (a zero-alpha
 * colour is passed straight through, and Glass mode skips capture when the plate opacity is zero). The consumers are
 * {@link com.seagull.liquidglass.client.mixin.NameTagGlassMixin} (Vanilla / Off restyle + Glass cancel) and
 * {@link dev.s1mp1e.client.gui.NameTagGlass} (Glass GUI-stage refraction).
 */
public final class NameTagModule extends Module {

    /** Background look. English keys; the ClickGUI shows them through {@code Lang.mode}. */
    public final Setting background = add(Setting.mode("Background", "Glass", "Glass", "Vanilla", "Off"));

    /** Last-constructed instance, read on the mixin's per-tag hot path to avoid a manager lookup. */
    private static volatile NameTagModule instance;

    public NameTagModule() {
        super("NameTags", "Visual");
        this.enabled = true;   // on by default: preserves the frosted-glass plate the mod has always drawn
        instance = this;
    }

    /**
     * Adjust the vanilla name-tag {@code backgroundColor} (packed ARGB) for the current setting.
     *
     * <p>Returns the colour unchanged when the module is off, the mode is {@code Vanilla}, or the
     * plate is already invisible (alpha 0 — a discrete / no-plate name). In {@code Off} the alpha is
     * cleared so no plate shows; in {@code Glass} the alpha is kept and only the RGB is retinted.
     */
    public static int plate(int vanillaArgb) {
        NameTagModule m = instance;
        if (m == null || !m.enabled) return vanillaArgb;

        int a = vanillaArgb >>> 24 & 0xFF;
        if (a == 0) return vanillaArgb;                      // discrete / no-plate: never add one

        String mode = m.background.modeValue;
        if ("Off".equals(mode))     return vanillaArgb & 0x00FFFFFF;   // zero alpha -> background hidden
        if ("Vanilla".equals(mode)) return vanillaArgb;                // untouched translucent black
        // Glass mode is handled entirely at the GUI stage (real refraction, see NameTagGlass): the vanilla submit is
        // cancelled before it reaches this @ModifyArg, so a Glass-mode value never actually passes through here. Leave
        // it untouched as a harmless fallback should the cancel ever miss.
        return vanillaArgb;
    }

    /** True when the module is on and the Background look is {@code Glass} — the GUI-stage refraction path. The name-tag
     *  submit hook reads this on its hot path to decide whether to capture + cancel the vanilla tag. */
    public static boolean glassMode() {
        NameTagModule m = instance;
        return m != null && m.enabled && "Glass".equals(m.background.modeValue);
    }
}
