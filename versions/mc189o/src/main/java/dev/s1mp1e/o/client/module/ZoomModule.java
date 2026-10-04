package dev.s1mp1e.o.client.module;

import dev.s1mp1e.o.client.KeyCodes;
import dev.s1mp1e.o.client.Module;
import dev.s1mp1e.o.client.Setting;
import net.minecraft.client.Minecraft;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.Display;

/**
 * Hold-key FOV zoom in the Zoomify style: while the bound key is held the rendered field of view
 * eases SMOOTHLY down to the target and springs back on release (not an instant snap), and the
 * mouse-look speed is scaled by the same factor so aiming stays "silky" while zoomed — the same
 * algorithm Zoomify uses. On 1.8.9 the FOV is applied through {@code CameraEvents.onFov}
 * ({@code EntityViewRenderEvent.FOVModifier}) and the look scale through
 * {@code CameraHooks.scaleLook} (spliced into {@code GameRenderer.render}).
 * Borderline fair-play — a view zoom only (OptiFine ships the same feature on 1.8.9); it reads no
 * target and gives no mechanical edge.
 *
 * <p>{@code Key} is a GLFW key code (default C = 67), translated to LWJGL2 at read time through
 * {@link KeyCodes} so the stored/displayed number matches every other S1mp1e version.
 * {@code Zoom} is the FOV multiplier held while zooming (smaller = more zoom). {@code Smoothness}
 * tunes the ease speed (higher = snappier).
 *
 * <p>{@code Block other zoom} (default on): while this module is enabled (and has a key), other mods'
 * CAMERA zoom keys never report pressed, so e.g. OptiFine's zoom / Essential's built-in Zoom (both
 * default C) no longer stack their FOV change on top of ours. The blocking is done at the vanilla
 * {@code KeyBinding.isPressed/isPressed} and {@code GameOptions.isPressed} level for every mod
 * ({@link ForeignZoomKeys} picks the keys; map/minimap zoom keys are never touched);
 * {@link EssentialZoomCompat}/{@link ZoomifyZoomCompat}/{@link PatcherZoomCompat} additionally clear a
 * zoom that another mod's "toggle" mode had already latched on. Our own zoom is unaffected: it reads the
 * key straight from LWJGL and registers no KeyBinding.
 */
public final class ZoomModule extends Module {

    private static ZoomModule instance;

    public final Setting key    = add(Setting.integer("Key (GLFW)", 67, 0, 400));   // GLFW_KEY_C
    public final Setting zoom   = add(Setting.number("Zoom", 0.30D, 0.10D, 0.90D));
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
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.screen != null) return false;
        // LWJGL2 delivers no key-up while the window is unfocused, so a zoom key released
        // during an alt-tab would stay latched "down" and leave the FOV stuck zoomed until
        // it is pressed+released again after refocus. Treat focus loss as not-zooming, so the
        // smooth ease springs the FOV back to 1.0 — matching the menu-key discipline in
        // KeybindHandler (which also guards on Display.isActive()).
        if (!Display.isActive()) return false;
        int lw = KeyCodes.glfwToLwjgl(k);
        if (lw <= 0) return false;
        try {
            return Keyboard.isKeyDown(lw);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Hot-path gate for the foreign-zoom blockers (called from the {@code KeyBinding}/{@code GameOptions}
     * key filters, but only when that key is actually pressed): true while this module is enabled, has a key
     * bound, and "Block other zoom" is on. Three field reads, no allocation. With our key unbound (0) nothing
     * is blocked, so the player is never left without any zoom at all.
     */
    public static boolean blocksForeignZoom() {
        ZoomModule m = instance;
        return m != null && m.enabled && m.blockOthers.boolValue && m.key.intValue > 0;
    }

    /**
     * Once per rendered frame (from {@link #smoothFactor()}): while blocking, give the compat layers their
     * per-frame check, flagging the first frame of a blocking period so a latched toggle-zoom is cleared exactly then.
     */
    public static void tickForeignZoomBlock() {
        boolean blocking = blocksForeignZoom();
        if (blocking) {
            boolean started = !wasBlockingForeign;
            EssentialZoomCompat.whileBlocking(started);
            if (started) {
                ZoomifyZoomCompat.onBlockingStarted();   // un-latch a Zoomify toggle zoom (no-op on 1.8.9)
                PatcherZoomCompat.onBlockingStarted();   // un-latch Patcher's OptiFine toggle-to-zoom
            }
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
     * the FOV event; time-based so multiple calls per frame stay correct.
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
