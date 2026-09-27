package dev.s1mp1e.client.module;

import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.Setting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.effect.StatusEffects;

/**
 * Lowers and fades the first-person fire overlay while burning (Lunar/Badlion "low fire": a view-comfort option, the
 * fire state itself is still obvious - the flames stay visible, just lower and see-through). Applied by
 * {@code LowFireMixin} around {@code InGameOverlayRenderer.renderFireOverlay}: the matrix stack is shifted down by
 * {@link #drop()} (screen-effect units, vanilla already sits the flames at -0.3) and the quad colour's alpha is scaled.
 * 1.18.2 port of the 26.2 module.
 */
public final class LowFireModule extends Module {
    public final Setting lower   = add(Setting.number("Fire lower", 0.3D, 0.0D, 0.6D));
    public final Setting opacity = add(Setting.number("Fire opacity", 0.6D, 0.1D, 1.0D));
    public final Setting size        = add(Setting.number("Fire size", 1.0D, 0.4D, 1.0D));
    public final Setting hideAll     = add(Setting.bool("Hide fire", false));
    public final Setting hideFireRes = add(Setting.bool("Hide with Fire Res", false));

    private static LowFireModule instance;

    public LowFireModule() {
        super("LowFire", "Combat");
        this.enabled = true;
        instance = this;
    }

    public static boolean active() {
        LowFireModule m = instance;
        return m != null && m.enabled;
    }

    /** True when the fire overlay should not be drawn at all this frame. */
    public static boolean hidden() {
        LowFireModule m = instance;
        if (m == null || !m.enabled) return false;
        if (m.hideAll.boolValue) return true;
        if (!m.hideFireRes.boolValue) return false;
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.player != null && mc.player.hasStatusEffect(StatusEffects.FIRE_RESISTANCE);
    }

    public static float size() {
        LowFireModule m = instance;
        return m == null ? 1f : (float) m.size.doubleValue;
    }

    public static float drop() {
        LowFireModule m = instance;
        return m == null ? 0f : (float) m.lower.doubleValue;
    }

    /** A 0..1 alpha scaled by the opacity setting. */
    public static float scaleAlpha(float a) {
        LowFireModule m = instance;
        return m == null ? a : Math.max(0f, Math.min(1f, a * (float) m.opacity.doubleValue));
    }
}
