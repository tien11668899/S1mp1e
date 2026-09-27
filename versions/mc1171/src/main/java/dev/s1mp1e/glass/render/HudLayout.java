package dev.s1mp1e.glass.render;

/**
 * Hotbar / HUD geometry shared by the InGameHud mixin and the HUD modules that line up with it
 * (XP-flow and hunger/saturation glints). Mixin classes cannot be referenced from ordinary code, so the
 * numbers live here in a plain holder and both sides read the same constants; this prevents the drift seen
 * on 1.20.1, where the mixin and the modules each carried their own copy of the lift.
 */
public final class HudLayout {

    private HudLayout() {}

    /** The glass hotbar is drawn this much larger than vanilla, scaled about its bottom-centre. */
    public static final float SCALE = 1.15f;

    /** The hotbar's bottom edge sits this many GUI pixels above the screen bottom. */
    public static final int LIFT = 4;

    /**
     * Status bars (health / armour / food / air) and the XP bar are raised by this many GUI pixels so they
     * clear the enlarged hotbar with a clean gap. 8 = LIFT + 4, the value 1.19.2 already uses and the same as
     * 1.21.1 and 26.2.
     */
    public static final int DECO_LIFT = 8;
}
