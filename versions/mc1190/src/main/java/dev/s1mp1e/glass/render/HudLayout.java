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
     * clear the enlarged hotbar with a clean gap.
     *
     * <p>Derived, not a bare constant, so it tracks {@link #SCALE}: the bar is grown 1.15x about its bottom,
     * which lifts its TOP edge by {@code ceil(22*(SCALE-1)) = 4} px, so the status cluster must clear
     * {@code LIFT (4)} + that {@code 4} + a {@code 3}px margin = {@code 11}. The old value 8 ({@code LIFT + 4})
     * omitted the scaled-top term, so on 1.19.2 the status bars sat 3 GUI px (6 physical at scale 2) LOWER than
     * the approved 1.20.1 reference — measured hearts y=628 vs the reference's y=622. This formula matches the
     * 1.20.1 build exactly.
     */
    public static final int DECO_LIFT = LIFT + (int) Math.ceil(22 * (SCALE - 1)) + 3;
}
