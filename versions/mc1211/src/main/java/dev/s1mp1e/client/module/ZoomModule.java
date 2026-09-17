package dev.s1mp1e.client.module;

import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.Setting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.InputUtil;

/**
 * Hold-key FOV zoom in the Zoomify style: while the bound key is held the rendered field of view
 * eases SMOOTHLY down to the target and springs back on release (not an instant snap), and the
 * mouse-look speed is scaled by the same factor so aiming stays "silky" while zoomed — the same
 * algorithm Zoomify uses. See {@code ZoomFovMixin} (FOV) and {@code MouseZoomSensitivityMixin}
 * (look scale). Borderline fair-play — a view zoom only (Zoomify is already installed here); it
 * reads no target and gives no mechanical edge.
 *
 * <p>{@code Key} is a GLFW key code (default C = 67). {@code Zoom} is the FOV multiplier held while
 * zooming (smaller = more zoom). {@code Smoothness} tunes the ease speed (higher = snappier).
 *
 * <p>{@code Block other zoom} (default on): while this module is enabled (and has a key), other mods'
 * CAMERA zoom keys never report pressed, so e.g. Essential's built-in Zoom / Zoomify / OK Zoomer (all
 * default C) no longer stack their FOV change on top of ours. {@code ForeignZoomKeyBlockMixin} does it at
 * the vanilla {@code KeyBinding.isPressed/wasPressed} level for every mod ({@link ForeignZoomKeys} picks
 * the keys; map/minimap zoom keys are never touched); {@link EssentialZoomCompat} additionally clears a
 * zoom that Essential's "Toggle to Zoom" had already latched on, and {@link ZoomifyZoomCompat} does the same
 * for Zoomify's toggle / secondary zoom. Our own zoom is unaffected: it reads the
 * key straight from GLFW and registers no KeyBinding.
 */
public final class ZoomModule extends Module {

    private static ZoomModule instance;

    public final Setting key   = add(Setting.integer("Key (GLFW)", 67, 0, 400));   // GLFW_KEY_C
    public final Setting zoom  = add(Setting.number("Zoom", 0.30D, 0.10D, 0.90D));
    public final Setting smooth = add(Setting.number("Smoothness", 14.0D, 4.0D, 40.0D));
    public final Setting blockOthers = add(Setting.bool("Block other zoom", true));

    // Eased state (Zoomify-like silky transition), advanced by real elapsed time.
    private static double curFactor = 1.0;
    private static long lastNano = 0L;
    // "Block other zoom" edge tracking (render thread).
    private static boolean wasBlockingForeign = false;

    public ZoomModule() { super("Zoom", "Visual"); instance = this; }

    /** @return true when the module is on and the bound key is currently held (in-world only). */
    public static boolean zooming() {
        ZoomModule m = instance;
        if (m == null || !m.enabled) return false;
        int k = m.key.intValue;
        if (k <= 0) return false;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.getWindow() == null || mc.currentScreen != null) return false;
        return InputUtil.isKeyPressed(mc.getWindow().getHandle(), k);
    }

    /**
     * Hot-path gate for the foreign-zoom blockers (called from {@code KeyBinding.isPressed} RETURN, but only
     * when that key is actually pressed): true while this module is enabled, has a key bound, and
     * "Block other zoom" is on. Three field reads, no allocation. With our key unbound (0) nothing is
     * blocked, so the player is never left without any zoom at all.
     */
    public static boolean blocksForeignZoom() {
        ZoomModule m = instance;
        return m != null && m.enabled && m.blockOthers.boolValue && m.key.intValue > 0;
    }

    /**
     * Once per rendered frame (from {@link #smoothFactor()}): while blocking, give the Essential compat layer its
     * per-frame check, flagging the first frame of a blocking period so a latched toggle-zoom is cleared exactly then.
     */
    public static void tickForeignZoomBlock() {
        boolean blocking = blocksForeignZoom();
        if (blocking) {
            boolean started = !wasBlockingForeign;
            EssentialZoomCompat.whileBlocking(started);
            if (started) ZoomifyZoomCompat.onBlockingStarted();   // un-latch a Zoomify toggle zoom
        }
        wasBlockingForeign = blocking;
    }

    /** Target FOV multiplier while zooming. */
    private static double target() {
        ZoomModule m = instance;
        return m == null ? 1.0 : m.zoom.doubleValue;
    }

    /**
     * The SMOOTH FOV multiplier this frame — eased toward the target (zooming) or 1.0 (released) with
     * a time-based exponential curve, so the transition is silky both ways. Called once per frame from
     * the FOV mixin; time-based so multiple calls per frame stay correct.
     */
    public static double smoothFactor() {
        tickForeignZoomBlock();
        long now = System.nanoTime();
        double dt = (lastNano == 0L) ? (1.0 / 60.0) : (now - lastNano) / 1.0e9;
        lastNano = now;
        if (dt < 0) dt = 0;
        if (dt > 0.25) dt = 0.25;   // clamp after a pause so it doesn't jump

        ZoomModule m = instance;
        double speed = m == null ? 14.0 : m.smooth.doubleValue;
        double tgt = zooming() ? target() : 1.0;
        double a = 1.0 - Math.exp(-dt * speed);
        curFactor += (tgt - curFactor) * a;
        if (Math.abs(curFactor - tgt) < 3.0e-4) curFactor = tgt;
        return curFactor;
    }

    /** Look-sensitivity scale = the current zoom factor while zoomed in (so on-screen aim speed stays
     *  constant, the Zoomify feel), or 1.0 when not zoomed. Reads the eased state without advancing it. */
    public static double lookScale() {
        return curFactor < 0.999 ? curFactor : 1.0;
    }
}
