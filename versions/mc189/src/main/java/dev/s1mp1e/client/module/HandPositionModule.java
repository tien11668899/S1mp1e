package dev.s1mp1e.client.module;

import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.Setting;

/**
 * Nudges the first-person hand/item model in view space. On 1.8.9 only the main hand exists,
 * so the {@code Off X/Y/Z} settings are kept for cross-version config-schema parity but have no
 * visible effect here. Purely cosmetic — it only translates the render matrix inside
 * {@code ItemRenderer.renderItemInFirstPerson} (via {@code CameraHooks.handOffset}); it never
 * changes hit registration, reach or timing. Offsets are in view-space units (+X right, +Y up,
 * +Z toward the camera).
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

    /** Off-hand offset {x,y,z}. 1.8.9 has no off-hand, so this is inert (schema parity only). */
    public static float[] offOffset() {
        HandPositionModule m = instance;
        if (m == null) return ZERO;
        return new float[] { (float) m.offX.doubleValue, (float) m.offY.doubleValue, (float) m.offZ.doubleValue };
    }

    private static final float[] ZERO = { 0f, 0f, 0f };
}
