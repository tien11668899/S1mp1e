package dev.s1mp1e.client.module;

import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.Setting;

/**
 * Fullbright: raises the lightmap gamma so dark areas (caves, night) render as bright as the
 * surface. {@code LightmapGammaMixin} boosts the value read from the gamma option inside
 * {@code LightmapRenderStateExtractor.extract} (26.2: it becomes the lightmap shader's
 * {@code BrightnessFactor} uniform), bypassing the option's 0..1 clamp. A render-side
 * brightness setting (like Lunar/Badlion Fullbright) — it does NOT reveal anything through
 * blocks (no x-ray); you still only see blocks you could already see, just lit.
 *
 * <p>{@code Brightness} is the effective gamma: ~15 = full bright, 1 = vanilla "Bright".
 */
public final class FullbrightModule extends Module {

    private static FullbrightModule instance;

    public final Setting brightness = add(Setting.number("Brightness", 15.0D, 1.0D, 20.0D));

    public FullbrightModule() { super("Fullbright", "Visual"); instance = this; }

    public static boolean active() {
        FullbrightModule m = instance;
        return m != null && m.enabled;
    }

    /** The gamma value to force while active. */
    public static double level() {
        FullbrightModule m = instance;
        return m == null ? 15.0D : m.brightness.doubleValue;
    }
}
