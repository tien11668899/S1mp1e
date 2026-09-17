package dev.s1mp1e.client.module;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * "Block other zoom", Zoomify part (reflective, optional). Zoomify's secondary zoom is always a toggle, and its main zoom
 * can be set to toggle. {@code Zoomify.tick} flips {@code zooming} / {@code secondaryZooming} on {@code consumeClick()} of
 * its keys, which the key blocker makes return false, so a zoom latched ON before blocking started could never be
 * switched off again. When blocking starts we clear both flags. In hold mode Zoomify recomputes them from the (blocked)
 * key every tick anyway.
 *
 * <p>Verified with javap in Zoomify 2.16.1+26.2, 2.15.2+1.21.1 and 2.15.2+1.20.1: {@code dev.isxander.zoomify.Zoomify}
 * (Kotlin object) has {@code private static boolean zooming} and {@code private static boolean secondaryZooming}. No
 * Minecraft types, so this one file is shared by all versions. Zoomify absent → silent no-op; layout changed → one log
 * line and it turns itself off.
 */
final class ZoomifyZoomCompat {

    private ZoomifyZoomCompat() {}

    /** 0 = not resolved yet, 1 = ready, 2 = unavailable. Render thread only. */
    private static int state;
    private static Field zooming, secondaryZooming;

    static void onBlockingStarted() {
        if (state == 2) return;
        try {
            if (state == 0) {
                Class<?> c;
                try {
                    c = Class.forName("dev.isxander.zoomify.Zoomify", false, ZoomifyZoomCompat.class.getClassLoader());
                } catch (ClassNotFoundException | LinkageError absent) {
                    state = 2;   // Zoomify not installed: normal, stay quiet
                    return;
                }
                zooming = staticBoolean(c, "zooming");
                secondaryZooming = staticBoolean(c, "secondaryZooming");
                state = 1;
            }
            zooming.setBoolean(null, false);
            secondaryZooming.setBoolean(null, false);
        } catch (Throwable t) {
            state = 2;
            System.out.println("[S1mp1e] Zoom: Zoomify toggle reset disabled (key blocking still active): " + t);
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
