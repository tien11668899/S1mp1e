package dev.s1mp1e.o.client.module;

import dev.s1mp1e.o.client.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.living.LivingEntity;

/**
 * Smooth sneak: 1.8.9 drops the camera 0.08 blocks the instant sneaking starts (and pops it back up on release).
 * Modern versions ease it instead — every client tick the camera's eye height moves halfway to the entity's eye height,
 * and frames interpolate between the last two ticks ({@code Camera.tick} / {@code Camera.setup}). This is that easing,
 * applied only where the camera is placed ({@code GameRenderer.transformCamera}); the crosshair ray, the hitbox and
 * everything the server sees keep vanilla's eye height.
 */
public final class SmoothSneakModule extends Module {

    private static SmoothSneakModule instance;
    private static Entity camera;
    private static float eye, prevEye;
    private static boolean ready;

    public SmoothSneakModule() {
        super("SmoothSneak", "Visual");
        this.enabled = true;
        instance = this;
    }

    /** End of every client tick: ease the eye height halfway to its target. */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        Entity cam = mc.world == null ? null : mc.getCamera();
        if (cam == null) { ready = false; camera = null; return; }
        float target = cam.getEyeHeight();
        // a new camera entity, or a jump larger than sneaking (sleeping, riding): no easing, start from here
        if (!ready || cam != camera || Math.abs(target - eye) > 0.5F) {
            eye = prevEye = target;
            camera = cam;
            ready = true;
            return;
        }
        prevEye = eye;
        eye += (target - eye) * 0.5F;
    }

    /** The eye height the camera is placed at this frame: the eased one, or vanilla's when off or not applicable. */
    public static float eyeHeight(Entity cam, float vanilla, float tickDelta) {
        SmoothSneakModule m = instance;
        if (m == null || !m.enabled || !ready || cam != camera) return vanilla;
        if (cam instanceof LivingEntity && ((LivingEntity) cam).isSleeping()) return vanilla;
        return prevEye + (eye - prevEye) * tickDelta;
    }

    /** The eased eye height at the last tick (DevShot). */
    public static float currentEye() { return eye; }
}
