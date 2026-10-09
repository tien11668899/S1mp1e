package dev.s1mp1e.client.module;

import dev.s1mp1e.client.Module;

/**
 * Holds the FOV steady: cancels the transient sprint / speed / effect FOV "punch" by forcing
 * the player's FOV multiplier to 1.0 (see {@code FovMultiplierMixin}; 26.2 target
 * {@code AbstractClientPlayer.getFieldOfViewModifier(boolean, float)}). Cosmetic — it only
 * changes the rendered field of view, never movement. Borderline fair-play (a steadier view),
 * but a standard Lunar/Badlion-style toggle.
 */
public final class SteadyFovModule extends Module {

    private static SteadyFovModule instance;

    public SteadyFovModule() { super("SteadyFOV", "Visual"); instance = this; }

    public static boolean active() {
        SteadyFovModule m = instance;
        return m != null && m.enabled;
    }
}
