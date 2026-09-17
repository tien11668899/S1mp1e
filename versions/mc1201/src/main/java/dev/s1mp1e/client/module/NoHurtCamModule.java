package dev.s1mp1e.client.module;

import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.Setting;

/**
 * Removes the damage camera-shake tilt and the red hurt/death flash (cosmetic only).
 * Mixin-driven on 1.21.1 — {@code GameRendererHurtTiltMixin} cancels the hurt tilt,
 * {@code LivingEntityRendererFlashMixin} suppresses the white/red overlay. Each is gated
 * by its own setting via the static accessors below (INSTANCE pattern like CpsModule).
 */
public final class NoHurtCamModule extends Module {
    private static NoHurtCamModule instance;

    public final Setting shake = add(Setting.bool("Camera shake", true));
    public final Setting flash = add(Setting.bool("Red flash", true));

    public NoHurtCamModule() { super("NoHurtCam", "Combat"); instance = this; }

    public boolean suppressShake() { return shake.boolValue; }
    public boolean suppressFlash() { return flash.boolValue; }

    /** Hot-path gate for the tilt mixin: true when the shake should be suppressed. */
    public static boolean shakeSuppressed() {
        NoHurtCamModule m = instance;
        return m != null && m.enabled && m.shake.boolValue;
    }

    /** Hot-path gate for the flash mixin: true when the hurt flash should be suppressed. */
    public static boolean flashSuppressed() {
        NoHurtCamModule m = instance;
        return m != null && m.enabled && m.flash.boolValue;
    }
}
