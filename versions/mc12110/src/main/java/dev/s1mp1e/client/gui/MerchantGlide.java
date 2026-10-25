package dev.s1mp1e.client.gui;

/**
 * One-frame hand-off from the villager-trade glass mixin to the shared button mixin, so the faint glass trade-button
 * capsules glide together with their item content during a sub-pixel trade scroll.
 *
 * <p>The trade-button widgets are drawn by the screen's widget pass, which runs slightly BEFORE the trade content /
 * scrollbar in {@code MerchantScreen.extractContents}; there is no single method that owns both. So the trade mixin
 * publishes the current glide offset here each frame and the button mixin reads it while rendering a trade button's
 * background — translating the capsule by the same fractional offset the item rows use. The value is a single float
 * written and read only on the render thread; a stale value simply means "not gliding" and hides nothing.
 */
public final class MerchantGlide {
    private MerchantGlide() {}

    private static boolean active;
    private static float   fracPx;

    /** Publish this frame's trade-list glide (called by the trade mixin's scroller redirect). */
    public static void set(boolean gliding, float fracPixels) {
        active = gliding;
        fracPx = fracPixels;
    }

    public static boolean active() { return active; }

    /** Sub-pixel offset (px) the visible trade rows are slid up by this frame. */
    public static float fracPx() { return fracPx; }
}
