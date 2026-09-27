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

    /** @param currentScreen the open screen (identity-compared), or null in-world */
    public static float value(Object currentScreen) {
        long now = System.nanoTime();
        if (currentScreen != screen) { screen = currentScreen; start = now; }
        float t = (now - start) / 1.0e9f / DURATION_S;
        return t <= 0f ? 0f : (t >= 1f ? 1f : t);
    }
}
