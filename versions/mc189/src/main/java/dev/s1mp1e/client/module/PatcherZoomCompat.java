package dev.s1mp1e.client.module;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * "Block other zoom", Patcher part (reflective, optional, 1.8.9-only). Sk1er's Patcher ships a
 * "Toggle to Zoom" tweak for OptiFine on 1.8.9; its {@code EntityRendererHook} keeps the toggle latch in
 * two private static booleans ({@code zoomToggled}, {@code isBeingHeld}). Layer 1
 * ({@link ForeignZoomKeys} via the {@code KeyBinding}/{@code GameSettings.isKeyDown} filters) already makes
 * OptiFine's zoom key never read pressed, which stops a HOLD zoom, but it cannot switch off a zoom Patcher
 * had already latched ON before blocking started, and with the key now unreadable the player could not
 * toggle it off. So on the first frame of a blocking period we clear both flags; OptiFine's own else-branch
 * then exits {@code zoomMode} and Patcher's redirect restores {@code smoothCamera}/sensitivity. We never
 * write {@code Config.zoomMode} ourselves — that would bypass Patcher's sensitivity restore.
 *
 * <p>Same shape as {@link ZoomifyZoomCompat}. Patcher absent → silent no-op; layout changed → one log line
 * and it turns itself off (layer 1 keeps blocking). Called from {@link ZoomModule#tickForeignZoomBlock()}.
 */
final class PatcherZoomCompat {

    private PatcherZoomCompat() {}

    /** 0 = not resolved yet, 1 = ready, 2 = unavailable. Render thread only. */
    private static int state;
    private static Field zoomToggled, isBeingHeld;

    static void onBlockingStarted() {
        if (state == 2) return;
        try {
            if (state == 0) {
                Class<?> c;
                try {
                    c = Class.forName("club.sk1er.patcher.hooks.EntityRendererHook", false,
                            PatcherZoomCompat.class.getClassLoader());
                } catch (ClassNotFoundException | LinkageError absent) {
                    state = 2;   // Patcher not installed: normal, stay quiet
                    return;
                }
                zoomToggled = staticBoolean(c, "zoomToggled");
                isBeingHeld = staticBoolean(c, "isBeingHeld");
                state = 1;
                System.out.println("[S1mp1e] Zoom: Patcher detected; 'Block other zoom' also resets its toggle-zoom latch");
            }
            zoomToggled.setBoolean(null, false);
            isBeingHeld.setBoolean(null, false);
        } catch (Throwable t) {
            state = 2;
            System.out.println("[S1mp1e] Zoom: Patcher toggle reset disabled (key blocking still active): " + t);
        }
    }

    private static Field staticBoolean(Class<?> c, String name) throws NoSuchFieldException {
        Field f = c.getDeclaredField(name);
        if (f.getType() != boolean.class || !Modifier.isStatic(f.getModifiers())) {
            throw new NoSuchFieldException(name + " is not a static boolean");
        }
        f.setAccessible(true);
        return f;
    }
}
