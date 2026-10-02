package dev.s1mp1e.glass.render;

/**
 * Single source of the HUD layout constants shared between the {@code InGameHudMixin} (which lifts and
 * upscales the vanilla status-bar / hotbar cluster) and the HUD modules that must anchor their overlays to
 * the moved bars ({@code HungerSaturationHudModule}, {@code XpFlowHudModule}). Mixin classes cannot be
 * referenced from ordinary code, so these live in a normal package instead of on the mixin.
 *
 * <p>The values mirror the current {@code InGameHudMixin}: the status bars and XP bar are raised by
 * {@link #DECO_LIFT} and the hotbar is upscaled by {@link #SCALE} about its bottom-centre (kept {@link #LIFT}
 * off the bottom). {@link #DECO_LIFT} on 1.15.2 is 8 (matching mc1211), NOT mc1201's 11.
 */
public final class HudLayout {

    private HudLayout() {}

    /** Pixels the upscaled hotbar is kept off the bottom edge. */
    public static final int LIFT = 4;

    /** Uniform upscale of the hotbar / status-bar cluster about its bottom-centre. */
    public static final float SCALE = 1.15f;

    /** Pixels the status bars (health / armor / food / air) and the XP bar are raised by. */
    public static final int DECO_LIFT = 8;
}
