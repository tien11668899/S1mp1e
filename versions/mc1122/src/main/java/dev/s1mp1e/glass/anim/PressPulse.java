package dev.s1mp1e.glass.anim;

import net.minecraft.client.gui.GuiButton;

import java.util.WeakHashMap;

/**
 * Tap feedback for buttons (group 9) — the 1.12.2 counterpart of mc1144's {@code PressPulse} / 26.2's
 * {@code ButtonPressPulseMixin}. On a press a button dips to ~95 % around its centre in ~50 ms and springs back over
 * ~0.25 s, like a tap on iOS. This owns only the clock: {@link #press} is stamped from the head of
 * {@code GuiButton.playPressSound} (every 1.12.2 click plays it; buttons have no keyboard activation in 1.12.2), and
 * {@link #scale} gives the current factor the draw site ({@code ButtonHook}, the settings shell) applies. Render thread.
 */
public final class PressPulse {

    private PressPulse() {}

    private static final float DEPTH = 0.05f;
    private static final float DOWN_S = 0.05f;
    private static final float BACK_TAU = 0.075f;

    private static final WeakHashMap<GuiButton, Long> PRESS = new WeakHashMap<GuiButton, Long>();

    public static void press(GuiButton w) {
        if (w != null) PRESS.put(w, System.nanoTime());
    }

    public static float scale(GuiButton w) {
        Long ns = PRESS.get(w);
        if (ns == null) return 1.0f;
        float t = (System.nanoTime() - ns) / 1.0e9f;
        float dip;
        if (t < DOWN_S) {
            float u = t / DOWN_S;
            dip = u * (2.0f - u);
        } else {
            dip = (float) Math.exp(-(t - DOWN_S) / BACK_TAU);
            if (dip < 0.004f) { PRESS.remove(w); return 1.0f; }
        }
        return 1.0f - DEPTH * dip;
    }
}
