package dev.s1mp1e.glass.anim;

import java.util.WeakHashMap;

import net.minecraft.client.gui.widget.ClickableWidget;

/**
 * Tap-feedback timing for buttons — the 1.21.1 counterpart of 26.2's {@code ButtonPressPulseMixin} state machine.
 *
 * <p>On activation (a click, or Enter/Space while focused) a button dips to ~95 % around its centre in ~50 ms and
 * springs back over ~0.25 s, like a tap on iOS. This class only owns the CLOCK: {@link #press} stamps the press time
 * per widget (keyed in a {@link WeakHashMap} so dead widgets evict themselves and coexisting buttons never cross-wire),
 * and {@link #scale} returns the current uniform scale factor (1.0 = settled / not recently pressed). The pose scale
 * itself is applied at the draw site (glass capsule + label) by {@code ButtonGlassMixin}. Render thread only.
 */
public final class PressPulse {

    private PressPulse() {}

    /** Dip depth (5 %), ease-in time, spring-back time constant — 26.2's LG_DEPTH / LG_DOWN_S / LG_BACK_TAU. */
    private static final float DEPTH = 0.05f;
    private static final float DOWN_S = 0.05f;
    private static final float BACK_TAU = 0.075f;

    private static final WeakHashMap<ClickableWidget, Long> PRESS = new WeakHashMap<>();

    /** Stamp {@code w} as pressed now. */
    public static void press(ClickableWidget w) {
        if (w != null) PRESS.put(w, System.nanoTime());
    }

    /** Current uniform scale for {@code w}: 1.0 unless it was pressed within the last ~0.25 s. */
    public static float scale(ClickableWidget w) {
        Long ns = PRESS.get(w);
        if (ns == null) return 1.0f;
        float t = (System.nanoTime() - ns) / 1.0e9f;
        float dip;
        if (t < DOWN_S) {
            float u = t / DOWN_S;
            dip = u * (2.0f - u);                                       // ease-out into the press
        } else {
            dip = (float) Math.exp(-(t - DOWN_S) / BACK_TAU);           // spring back, no overshoot
            if (dip < 0.004f) { PRESS.remove(w); return 1.0f; }
        }
        return 1.0f - DEPTH * dip;
    }
}
