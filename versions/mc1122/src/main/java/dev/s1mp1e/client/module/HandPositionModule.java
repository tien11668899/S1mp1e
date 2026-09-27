package dev.s1mp1e.client.module;

import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.Setting;

/**
 * Nudges the first-person hand/item model in view space, INDEPENDENTLY for the main hand
 * and the off hand. 1.12.2 has a real off-hand, so all six settings are live: the offset is
 * applied by {@code CameraHooks.handOffset(EnumHand)}, spliced right after the first
 * {@code pushMatrix} of the per-hand {@code ItemRenderer.renderItemInFirstPerson}, i.e. inside
 * vanilla's own per-hand push/pop, so the two offsets never leak into each other. Purely
 * cosmetic — it only translates the render matrix; it never changes hit registration, reach or
 * timing. Offsets are in view-space units (+X right, +Y up, +Z toward the camera).
 */
public final class HandPositionModule extends Module {

    private static HandPositionModule instance;

    public final Setting mainX = add(Setting.number("Main X", 0.0D, -1.0D, 1.0D));
    public final Setting mainY = add(Setting.number("Main Y", 0.0D, -1.0D, 1.0D));
    public final Setting mainZ = add(Setting.number("Main Z", 0.0D, -1.0D, 1.0D));
    public final Setting offX  = add(Setting.number("Off X", 0.0D, -1.0D, 1.0D));
    public final Setting offY  = add(Setting.number("Off Y", 0.0D, -1.0D, 1.0D));
    public final Setting offZ  = add(Setting.number("Off Z", 0.0D, -1.0D, 1.0D));

    public HandPositionModule() { super("HandPosition", "Visual"); instance = this; }

    /** @return true when the offset should be applied at all. */
    public static boolean active() {
        HandPositionModule m = instance;
        return m != null && m.enabled;
    }

    /** Main-hand offset {x,y,z}. */
    public static float[] mainOffset() {
        HandPositionModule m = instance;
        if (m == null) return ZERO;
        return new float[] { (float) m.mainX.doubleValue, (float) m.mainY.doubleValue, (float) m.mainZ.doubleValue };
    }

    /** Off-hand offset {x,y,z} (live on 1.12.2). */
    public static float[] offOffset() {
        HandPositionModule m = instance;
        if (m == null) return ZERO;
        return new float[] { (float) m.offX.doubleValue, (float) m.offY.doubleValue, (float) m.offZ.doubleValue };
    }

    private static final float[] ZERO = { 0f, 0f, 0f };
}
