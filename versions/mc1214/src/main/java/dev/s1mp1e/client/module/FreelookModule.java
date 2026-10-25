package dev.s1mp1e.client.module;

import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.Setting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;

/**
 * Hold-key freelook: while the key is held the mouse turns the CAMERA instead of the player, so you can look around
 * (by default from third person) while still facing and moving the way you were. On release the camera eases back to
 * the player's view instead of snapping. Render-only: the player's own rotation, aim and movement never change — the
 * mouse deltas are simply not applied to the player while held ({@code FreelookMouseMixin}), and the camera reads
 * our angles instead of the player's ({@code FreelookCameraMixin}).
 *
 * <p>{@code Key} is a GLFW key code like {@link ZoomModule}'s; default Left Alt = 342. {@code Third person} switches
 * to the back view while held and restores the previous view afterwards (unless the player changed views meanwhile).
 */
public final class FreelookModule extends Module {

    private static FreelookModule instance;

    public final Setting key         = add(Setting.key("Key (GLFW)", 342));   // GLFW_KEY_LEFT_ALT
    public final Setting thirdPerson = add(Setting.bool("Third person", true));
    public final Setting smoothBack  = add(Setting.bool("Smooth return", true));
    public final Setting backSpeed   = add(Setting.number("Return speed", 12.0D, 4.0D, 40.0D));

    private static boolean active;      // the camera is ours (held, or easing back)
    private static boolean returning;   // released: easing back to the player's view; mouse goes to the player again
    private static float yaw, pitch;
    private static Perspective restoreTo; // the view to restore on finish, or null when we did not change it
    private static long lastNano;

    public FreelookModule() { super("Freelook", "Visual"); instance = this; }

    private static boolean held() {
        FreelookModule m = instance;
        if (m == null || !m.enabled) return false;
        int k = m.key.intValue;
        if (k <= 0) return false;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.getWindow() == null || mc.currentScreen != null || mc.player == null) return false;
        return InputUtil.isKeyPressed(mc.getWindow().getHandle(), k);
    }

    /** Once per rendered frame, before the camera copies its entity's rotation. */
    public static void frame() {
        long now = Util.getMeasuringTimeNano();
        double dt = lastNano == 0L ? 1.0 / 60.0 : (now - lastNano) / 1.0e9;
        lastNano = now;
        if (dt < 0) dt = 0;
        if (dt > 0.25) dt = 0.25;

        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity p = mc.player;
        if (p == null) { reset(); return; }
        boolean down = held();

        if (down && !active) {
            active = true;
            returning = false;
            yaw = p.getYaw();
            pitch = p.getPitch();
            if (instance.thirdPerson.boolValue && mc.options.getPerspective() != Perspective.THIRD_PERSON_BACK) {
                restoreTo = mc.options.getPerspective();
                mc.options.setPerspective(Perspective.THIRD_PERSON_BACK);
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
        float dy = MathHelper.wrapDegrees(p.getYaw() - yaw);
        float dp = p.getPitch() - pitch;
        yaw += dy * a;
        pitch += dp * a;
        if (Math.abs(dy) < 0.3F && Math.abs(dp) < 0.3F) finish();
    }

    /** Client tick: leaving the world mid-freelook must not strand the view in third person. */
    public static void tick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (active && (mc.player == null || mc.world == null)) reset();
    }

    private static void finish() {
        active = false;
        returning = false;
        MinecraftClient mc = MinecraftClient.getInstance();
        // Restore only if the view is still the one we set — an F5 press during freelook is the player's choice.
        if (restoreTo != null && mc.options != null && mc.options.getPerspective() == Perspective.THIRD_PERSON_BACK)
            mc.options.setPerspective(restoreTo);
        restoreTo = null;
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

    /** Same scale and pitch clamp as {@code Entity.changeLookDirection}, applied to the camera's angles instead. */
    public static void turn(double dx, double dy) {
        yaw += (float) dx * 0.15F;
        pitch = MathHelper.clamp(pitch + (float) dy * 0.15F, -90.0F, 90.0F);
    }
}
