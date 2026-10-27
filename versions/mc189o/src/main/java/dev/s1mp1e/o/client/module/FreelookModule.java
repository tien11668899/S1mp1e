package dev.s1mp1e.o.client.module;

import dev.s1mp1e.o.client.KeyCodes;
import dev.s1mp1e.o.client.Module;
import dev.s1mp1e.o.client.Setting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.living.player.LocalClientPlayerEntity;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.Display;

/**
 * Hold-key freelook (ported from 26.3): while the key is held the mouse turns the CAMERA instead of the player, so you
 * can look around (by default from third person) while still facing and moving the way you were; on release the
 * camera eases back to the player's view instead of snapping. Render-only: the player's own rotation, aim and movement
 * never change — the mouse delta is simply not applied to the player while held, and the camera, the name-tag facing
 * and the particle facing read our angles instead of the player's ({@code FreelookMixin}).
 *
 * <p>{@code Key (GLFW)} is a GLFW key code like {@link ZoomModule}'s (default Left Alt = 342), translated to LWJGL2 at
 * read time so the shared config means the same key on every version. {@code Third person} switches to the back view
 * while held and restores the previous view afterwards (unless the player changed views meanwhile).
 */
public final class FreelookModule extends Module {

    private static FreelookModule instance;

    public final Setting key         = add(Setting.integer("Key (GLFW)", 342, 0, 400));   // GLFW_KEY_LEFT_ALT
    public final Setting thirdPerson = add(Setting.bool("Third person", true));
    public final Setting smoothBack  = add(Setting.bool("Smooth return", true));
    public final Setting backSpeed   = add(Setting.number("Return speed", 12.0D, 4.0D, 40.0D));

    private static boolean active;      // the camera is ours (held, or easing back)
    private static boolean returning;   // released: easing back to the player's view; mouse goes to the player again
    private static float yaw, pitch;
    private static int restoreTo = -1;  // the perspective to restore on finish, -1 when we did not change it
    private static long lastNano;
    /** DevShot only: stands in for the held key (the key is read from the physical keyboard, which a test can't press). */
    public static boolean devHold;

    public FreelookModule() { super("Freelook", "Visual"); instance = this; }

    private static boolean held() {
        FreelookModule m = instance;
        if (m == null || !m.enabled) return false;
        if (devHold) return Minecraft.getInstance().player != null;
        int k = m.key.intValue;
        if (k <= 0) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.screen != null || mc.player == null) return false;
        // LWJGL2 delivers no key-up while the window is unfocused: a key released during an alt-tab must not stay held
        if (!Display.isActive()) return false;
        int lw = KeyCodes.glfwToLwjgl(k);
        if (lw <= 0) return false;
        try {
            return Keyboard.isKeyDown(lw);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Once per rendered frame, before the camera is set up (GameRenderer.render HEAD). */
    public static void frame() {
        long now = System.nanoTime();
        double dt = lastNano == 0L ? 1.0 / 60.0 : (now - lastNano) / 1.0e9;
        lastNano = now;
        if (dt < 0) dt = 0;
        if (dt > 0.25) dt = 0.25;

        Minecraft mc = Minecraft.getInstance();
        LocalClientPlayerEntity p = mc.player;
        if (p == null) { reset(); return; }
        boolean down = held();

        if (down && !active) {
            active = true;
            returning = false;
            yaw = p.yaw;
            pitch = p.pitch;
            if (instance.thirdPerson.boolValue && mc.options.perspective != 1) {
                restoreTo = mc.options.perspective;
                mc.options.perspective = 1;
            }
            return;
        }
        if (!active) return;
        if (down) { returning = false; return; }   // pressed again while easing back: keep looking from here

        if (!returning) {
            if (instance == null || !instance.smoothBack.boolValue) { finish(); return; }
            returning = true;
        }
        double speed = instance == null ? 12.0 : instance.backSpeed.doubleValue;
        float a = (float) (1.0 - Math.exp(-dt * speed));
        float dy = MathHelper.wrapDegrees(p.yaw - yaw);
        float dp = p.pitch - pitch;
        yaw += dy * a;
        pitch += dp * a;
        if (Math.abs(dy) < 0.3F && Math.abs(dp) < 0.3F) finish();
    }

    /** Client tick: leaving the world mid-freelook must not strand the view in third person. */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (active && (mc.player == null || mc.world == null)) reset();
    }

    private static void finish() {
        active = false;
        returning = false;
        Minecraft mc = Minecraft.getInstance();
        // Restore only if the view is still the one we set — an F5 press during freelook is the player's choice.
        if (restoreTo >= 0 && mc.options != null && mc.options.perspective == 1) mc.options.perspective = restoreTo;
        restoreTo = -1;
    }

    private static void reset() {
        if (active) finish();
        lastNano = 0L;
    }

    /** True while the camera uses our angles (held or easing back). */
    public static boolean active() { return active; }

    /** True while mouse movement should turn the camera instead of the player. */
    public static boolean capturing() { return active && !returning; }

    public static float yaw()   { return yaw; }
    public static float pitch() { return pitch; }

    /** A camera-entity yaw read by the view code: the freelook yaw while it holds the camera (only when the camera
     *  entity is the player itself — spectating another entity keeps that entity's view). */
    public static float viewYaw(float v)   { return active && cameraIsPlayer() ? yaw : v; }

    /** As {@link #viewYaw} for pitch. */
    public static float viewPitch(float v) { return active && cameraIsPlayer() ? pitch : v; }

    private static boolean cameraIsPlayer() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.getCamera() == mc.player;
    }

    /** Same scale, sign and pitch clamp as {@code Entity.updateLocalPlayerCamera}, applied to the camera's angles. */
    public static void turn(float dx, float dy) {
        yaw += dx * 0.15F;
        pitch = MathHelper.clamp(pitch - dy * 0.15F, -90.0F, 90.0F);
    }
}
