package dev.s1mp1e.o.client.module;

import dev.s1mp1e.o.client.Module;
import dev.s1mp1e.o.client.Setting;

/**
 * NameTags (ported from 26.3) — the background plate drawn behind entity / player name tags, chosen by
 * {@code Background}:
 * <ul>
 *   <li><b>Glass</b> (default) — the frosted charcoal plate S1mp1e has always drawn on 1.8.9
 *       ({@link dev.s1mp1e.o.glass.hook.GlassNameTag}). 1.8.9 draws name tags in world space with no backdrop to
 *       refract, so this stays a re-tint rather than 26.3's refraction.</li>
 *   <li><b>Vanilla</b> — the plate exactly as the game draws it (translucent black).</li>
 *   <li><b>Off</b> — the plate's alpha is zeroed, so only the name text shows.</li>
 * </ul>
 * Switching the module off is the same as Vanilla.
 *
 * <p><b>FAIR PLAY.</b> Only the plate the game already draws is restyled or hidden: no name is shown where vanilla
 * shows none, nothing is drawn through walls, and no entity data is read.
 */
public final class NameTagModule extends Module {

    public final Setting background = add(Setting.mode("Background", "Glass", "Glass", "Vanilla", "Off"));

    /** Last-constructed instance, read on the per-tag hot path to avoid a manager lookup. */
    private static volatile NameTagModule instance;

    public NameTagModule() {
        super("NameTags", "Visual");
        this.enabled = true;   // on by default: keeps the frosted plate 1.8.9 has always drawn
        instance = this;
    }

    /** The plate look in effect: "Glass", "Vanilla" or "Off" ("Vanilla" when the module is off). */
    public static String mode() {
        NameTagModule m = instance;
        if (m == null || !m.enabled) return "Vanilla";
        return m.background.modeValue;
    }
}
