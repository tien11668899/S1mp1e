package dev.s1mp1e.client.module;

import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.Setting;
import dev.s1mp1e.client.asm.CombatHooks;

/**
 * Removes the two damage "feedback" effects: the camera shake/tilt when the player
 * takes a hit, and the red tint painted over a hurt entity.
 *
 * <p><b>FAIR-PLAY:</b> render-only. Both effects are cosmetic — removing them shows
 * the player nothing they did not already have and changes nothing sent to the
 * server. The work is done entirely in {@link CombatHooks} via the render-class ASM
 * patches; this module just holds the two toggles and hands itself to the hooks so
 * they can read the live flags without a per-frame lookup.
 *
 * <p>The module carries no Forge event handlers: the ASM reads {@link #enabled} and
 * the two settings directly each frame, so there is nothing to register.
 */
public final class NoHurtCamModule extends Module {

    /** Suppress the {@code EntityRenderer.hurtCameraEffect} shake (and death spin). */
    private final Setting shake = add(Setting.bool("Camera shake", true));
    /** Suppress the red hurt/death tint from {@code RendererLivingEntity.setBrightness}. */
    private final Setting flash = add(Setting.bool("Red flash", true));

    public NoHurtCamModule() {
        super("NoHurtCam", "Visual");
        // Bind on construction: ModuleManager.init() builds every module before any
        // frame renders, so the hooks always have their reference by the time the
        // patched render methods first run.
        CombatHooks.bindNoHurtCam(this);
    }

    /** @return true when the camera shake should be removed. */
    public boolean suppressShake() { return shake.boolValue; }

    /** @return true when the red damage flash should be removed. */
    public boolean suppressFlash() { return flash.boolValue; }
}
