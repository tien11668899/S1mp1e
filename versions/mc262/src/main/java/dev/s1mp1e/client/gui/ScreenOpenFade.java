package dev.s1mp1e.client.gui;

/**
 * The one screen-open fade shared by every glass widget in vanilla menus (glass buttons and glass option sliders):
 * a linear 0 → 1 ramp over 150 ms, restarted whenever the current screen instance changes. Keyed by identity, so a
 * window resize (same screen object) doesn't restart it.
 *
 * <p>Shared on purpose. When buttons and sliders each kept their own "last screen", a slider that hadn't been painted
 * on the screen in between didn't notice a return to the same screen (e.g. Options → Controls → Done) and popped in
 * at full opacity while the buttons faded. Identical in every version (no Minecraft types); render thread only.
 */
public final class ScreenOpenFade {
    private ScreenOpenFade() {}

    private static final float DURATION_S = 0.15f;
    private static Object screen;
    private static long start;
    /** While {@code nanoTime < holdUntil} a screen cross-dissolve covers the switch: report fully open. */
    private static long holdUntil;

    /** @param currentScreen the open screen (identity-compared), or null in-world */
    public static float value(Object currentScreen) {
        long now = net.minecraft.util.Util.getNanos();
        if (currentScreen != screen) { screen = currentScreen; start = now; }   // keep the clock even while held
        if (now < holdUntil) return 1f;
        float t = (now - start) / 1.0e9f / DURATION_S;
        return t <= 0f ? 0f : (t >= 1f ? 1f : t);
    }

    /**
     * A snapshot cross-dissolve is fading the outgoing screen over the incoming one until {@code nanoTime}: the incoming
     * screen must be complete underneath from its first frame (else its labels show before its glass widgets), so the
     * open fade reads 1 until then. {@code 0} releases it. The clock above keeps running, so nothing restarts afterwards.
     */
    public static void holdUntil(long nanoTime) { holdUntil = nanoTime; }

    /** True while a screen cross-dissolve is covering the switch (see {@link #holdUntil}). */
    public static boolean held() { return net.minecraft.util.Util.getNanos() < holdUntil; }
}
