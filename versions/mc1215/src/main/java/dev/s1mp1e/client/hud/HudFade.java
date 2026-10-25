package dev.s1mp1e.client.hud;

import java.util.IdentityHashMap;

/**
 * Appear / disappear for HUD modules, so switching one on or off never makes it pop in or vanish.
 *
 * <p>{@link #visibility} tracks each module's enabled state and returns 0..1: when the state flips it ramps from wherever
 * it stands (so a quick on-off-on continues smoothly) to the new target over {@link #IN_S} / {@link #OUT_S}. The first
 * time a module is seen (world join) it snaps, matching the vanilla HUD, which also just appears. The HUD driver keeps
 * drawing a module while its visibility is above zero — including the fade-out after it was switched off — scaling it
 * about its own centre and publishing {@link #alpha} for the draw helpers ({@link dev.s1mp1e.client.gui.HudText},
 * {@link dev.s1mp1e.client.module.HudGlass}) to apply. Item icons can't take an alpha in vanilla; they ride the scale instead.
 *
 * <p>1.21.1 port of the 26.2 {@code dev.s1mp1e.client.hud.HudFade}. Identical logic; the one timestamp source
 * ({@code Util.getNanos} in 26.2) is {@link System#nanoTime()} here — the same monotonic clock the rest of the 1.21.1
 * animations use ({@code ScreenOpenFade}, module easing), and mapping-independent.
 *
 * <p>Render thread only. {@link #alpha} is 1 outside a module's draw call.
 */
public final class HudFade {
    private HudFade() {}

    /** Appear / disappear lengths — the appear matches the glass screen-open fade. */
    private static final float IN_S = 0.15F, OUT_S = 0.12F;
    /** Scale at visibility 0; grows to 1 with an ease-out. */
    public static final float SCALE_FROM = 0.85F;

    /** Alpha multiplier of the module currently drawing (1 outside a module's draw). */
    public static float alpha = 1F;

    private static final class State { boolean on; float from; long at; }
    private static final IdentityHashMap<Object, State> STATES = new IdentityHashMap<>();

    /** This frame's visibility 0..1 of {@code module}, whose enabled state is {@code enabled}; snaps on first sight. */
    public static float visibility(Object module, boolean enabled) {
        return visibility(module, enabled, true);
    }

    /**
     * This frame's visibility 0..1 of {@code key} (identity-compared), currently {@code enabled}. With
     * {@code snapFirst} the first sighting shows the state as is (a module on world join); without it, an element first
     * seen while on fades in from 0 (a status-effect icon's first sighting IS its appearance).
     */
    public static float visibility(Object key, boolean enabled, boolean snapFirst) {
        long now = System.nanoTime();
        State s = STATES.get(key);
        if (s == null) {
            s = new State();
            s.on = enabled;
            if (snapFirst || !enabled) {       // snap, no animation
                s.from = enabled ? 1F : 0F;
                s.at = 0L;
            } else {                           // fade in from nothing
                s.from = 0F;
                s.at = now;
            }
            STATES.put(key, s);
            return current(s, now);
        }
        if (enabled != s.on) {                 // flipped: continue from where it stands now
            s.from = current(s, now);
            s.on = enabled;
            s.at = now;
        }
        return current(s, now);
    }

    private static float current(State s, long now) {
        float target = s.on ? 1F : 0F;
        if (s.at == 0L) return target;
        float t = (now - s.at) / 1.0e9F / (s.on ? IN_S : OUT_S);
        if (t >= 1F) return target;
        return s.from + (target - s.from) * (t <= 0F ? 0F : t);
    }

    /** Drop {@code key}'s state (its element is gone for good). */
    public static void forget(Object key) {
        STATES.remove(key);
    }

    /** Cubic ease-out, for the scale. */
    public static float easeOut(float v) {
        float u = 1F - v;
        return 1F - u * u * u;
    }

    /** {@code argb} with its alpha scaled by {@link #alpha} (unchanged when fully visible). */
    public static int argb(int argb) {
        if (alpha >= 1F) return argb;
        int a = Math.round((argb >>> 24 & 0xFF) * alpha) & 0xFF;
        return a << 24 | argb & 0xFFFFFF;
    }
}
