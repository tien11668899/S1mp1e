package dev.s1mp1e.o.glass.render;

/**
 * The player tab list fades in on key-press AND out on release (group 7). Vanilla renders the list only while the
 * player-list key is held ({@code GuiIngameForge.renderPlayerList}) and pops it away the instant the key comes up.
 * {@link #gate(boolean)} is that render gate with the real key state: it keeps returning {@code true} for
 * {@link #OUT_S} after release while {@link #alpha()} eases to 0. The glass plates, row stripes, names, heads and ping
 * icons all take {@link #alpha()} ({@code HudMotionHook} / {@code GlassTabList}). Ported verbatim from 26.2 / mc1144.
 */
public final class TabListFade {
    private TabListFade() {}

    private static final float IN_S = 0.15F;
    private static final float OUT_S = 0.15F;

    private static boolean prevDown;
    private static long legNs;
    private static boolean out = true;
    private static float legFrom;
    private static float alpha;

    /** DEV-only (DevShot cannot hold a key): stands in for the player-list key. */
    public static boolean devDown;

    public static boolean gate(boolean realDown) {
        realDown = realDown || devDown;
        long now = System.nanoTime();
        if (realDown != prevDown) {
            legFrom = alpha;
            legNs = now;
            out = !realDown;
            prevDown = realDown;
        }
        float dur = out ? OUT_S : IN_S;
        float t = (now - legNs) / 1.0e9F / dur;
        t = t < 0F ? 0F : (t > 1F ? 1F : t);
        float target = out ? 0F : 1F;
        alpha = legFrom + (target - legFrom) * t;
        return realDown || alpha > 0.004F;
    }

    public static float alpha() {
        return alpha;
    }
}
