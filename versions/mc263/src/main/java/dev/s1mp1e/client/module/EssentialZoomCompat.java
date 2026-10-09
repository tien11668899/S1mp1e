package dev.s1mp1e.client.module;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * "Block other zoom", layer 2: Essential only, reflective, optional. Layer 1 ({@code ForeignZoomKeyBlockMixin})
 * makes Essential's Zoom key never read pressed, which fully stops Essential's HOLD zoom. It cannot undo a zoom
 * that Essential's "Toggle to Zoom" mode had already latched ON before blocking started: {@code ZoomHandler.getZoomState()}
 * then keeps returning its stored {@code isZoomToggled = true}, and since the key can no longer read pressed, the
 * player could not toggle it off again. So on the frame blocking starts we clear {@code isZoomToggled} and
 * {@code isZoomBeingHeld}. Essential's own released branch then runs on its next {@code applyModifiers} call. That
 * branch restores {@code Options.smoothCamera}, clears {@code isZoomActive} (the hand comes back) and stops cancelling
 * the scroll wheel.
 *
 * <p>Why reflection and not a {@code @Pseudo} mixin on {@code getZoomState()}: Essential's stage2 loader adds its real jar
 * to Knot's classpath during preLaunch, but Fabric has already prepared our mixin config by then. Mixin drops a
 * {@code @Pseudo} target it cannot find at prepare time and never maps it again, so the injection would silently never
 * apply. This was reproduced in the 26.2 dev client with a preLaunch entrypoint that adds a stand-in ZoomHandler jar
 * the same way.
 *
 * <p>Names are javap-verified in Essential 1.4.1.1 (fabric_26.2), 1.4.0.3 (fabric_1.21.1) and 1.3.10.9 (forge_1.8.9):
 * {@code public static ZoomHandler getInstance()}, {@code public boolean isZoomActive},
 * {@code private boolean isZoomToggled}, {@code private boolean isZoomBeingHeld}. There are no Minecraft types here,
 * so this one file is shared by 26.2, 1.21.1 and 1.20.1. If Essential is absent or renames any of these, this layer
 * turns itself off (one log line) and layer 1 keeps blocking.
 */
final class EssentialZoomCompat {

    private EssentialZoomCompat() {}

    private static final String HANDLER = "gg.essential.handlers.ZoomHandler";

    /** 0 = not resolved yet, 1 = ready, 2 = unavailable (Essential absent / layout changed / failed). */
    private static int state;
    private static Object handler;
    private static Field toggled, held, active;
    private static int activeWhileBlocked;
    private static boolean warnedStillActive;

    /**
     * Called from every FOV computation while blocking is on (render thread): once per frame on 26.2
     * ({@code Camera.calculateFov}), twice per frame on 1.21.1 / 1.20.1 ({@code GameRenderer.getFov} runs for the world
     * and again for the hand).
     *
     * @param justStarted true on the first frame of a blocking period (module/setting just switched on, or first
     *                    frame after config load)
     */
    static void whileBlocking(boolean justStarted) {
        if (state == 2) return;
        if (state == 0 && !resolve()) return;
        try {
            if (justStarted) {
                clearToggle();
                activeWhileBlocked = 0;
            } else if (active.getBoolean(handler)) {
                // Essential still reports an active zoom although its key can't read pressed and the toggle was
                // cleared. One or two calls of that are normal: Essential only notices on its NEXT applyModifiers,
                // and on yarn our hook runs again for the hand FOV before that. Persisting for 10+ calls means this
                // Essential build reads its key some other way. Clear again (cheap) and say so once.
                clearToggle();
                if (++activeWhileBlocked >= 10 && !warnedStillActive) {
                    warnedStillActive = true;
                    System.out.println("[S1mp1e] Zoom: Essential zoom is still active while 'Block other zoom' is on;"
                            + " this Essential build may read its Zoom key differently. Unbind Essential's Zoom key in Controls.");
                }
            } else {
                activeWhileBlocked = 0;
            }
        } catch (Throwable t) {
            state = 2;
            System.out.println("[S1mp1e] Zoom: Essential zoom compat disabled: " + t);
        }
    }

    private static void clearToggle() throws IllegalAccessException {
        toggled.setBoolean(handler, false);
        held.setBoolean(handler, false);
    }

    private static boolean resolve() {
        try {
            Class<?> c = Class.forName(HANDLER, false, EssentialZoomCompat.class.getClassLoader());
            Method getInstance = c.getMethod("getInstance");
            if (!Modifier.isStatic(getInstance.getModifiers())) throw new NoSuchMethodException("getInstance not static");
            Object h = getInstance.invoke(null);
            Field t = booleanField(c, "isZoomToggled");
            Field b = booleanField(c, "isZoomBeingHeld");
            Field a = booleanField(c, "isZoomActive");
            if (h == null) throw new IllegalStateException("ZoomHandler.getInstance() returned null");
            handler = h; toggled = t; held = b; active = a;
            state = 1;
            System.out.println("[S1mp1e] Zoom: Essential detected; 'Block other zoom' also resets its toggle-zoom state");
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            state = 2;                                  // Essential not installed: normal, stay quiet
            return false;
        } catch (Throwable t) {
            state = 2;
            System.out.println("[S1mp1e] Zoom: Essential ZoomHandler layout changed, toggle reset disabled"
                    + " (key blocking still active): " + t);
            return false;
        }
    }

    private static Field booleanField(Class<?> c, String name) throws NoSuchFieldException {
        Field f = c.getDeclaredField(name);
        if (f.getType() != boolean.class || Modifier.isStatic(f.getModifiers())) {
            throw new NoSuchFieldException(name + " is not an instance boolean");
        }
        f.setAccessible(true);
        return f;
    }
}
