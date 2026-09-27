package dev.s1mp1e.client.asm;

import dev.s1mp1e.client.module.ForeignZoomKeys;
import dev.s1mp1e.client.module.FullbrightModule;
import dev.s1mp1e.client.module.HandPositionModule;
import dev.s1mp1e.client.module.ZoomModule;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.settings.KeyBinding;

import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Static hook targets called from bytecode spliced by {@link CameraTransformer}.
 *
 * <p><b>These methods run in the render / input hot path and must never throw.</b> A
 * hook that raised out of {@code EntityRenderer.updateLightmap} or
 * {@code ItemRenderer.renderItemInFirstPerson} would crash the frame from inside MC
 * code we cannot try/catch, so every body here is wrapped and degrades to the vanilla
 * value on any failure.
 *
 * <p><b>FAIR-PLAY:</b> every method is render / own-input only. Nothing here reads a
 * targeted entity, a hit result, a distance, or any packet. {@link #gamma} raises
 * lightmap brightness (not x-ray); {@link #handOffset} nudges the drawn hand matrix;
 * {@link #scaleLook} (used by the later Zoom package) scales only the player's own
 * mouse delta; the two key filters only make OTHER mods' zoom keys read unpressed.
 *
 * <p>The transformer records, per splice, whether it actually matched, so event-based
 * fallbacks (e.g. the Fullbright {@code RenderTickEvent} bracket) can tell whether the
 * ASM path is live.
 */
public final class CameraHooks {

    private CameraHooks() {}

    // ---- per-patch success flags -------------------------------------------
    // The flags themselves live on CameraTransformer (writing a static field here
    // would class-load this hook DURING the transform of KeyBinding/GameSettings/
    // EntityRenderer). These are read-only views for runtime callers.

    /** True once the {@code EntityRenderer.updateLightmap} gamma splice matched at least once. */
    public static boolean gammaPatched()     { return CameraTransformer.gammaPatched; }
    /** True once the {@code ItemRenderer.renderItemInFirstPerson} hand splices matched. */
    public static boolean handPatched()      { return CameraTransformer.handPatched; }
    /** True once the mouse-look scaling splice matched (Zoom package). */
    public static boolean lookScalePatched() { return CameraTransformer.lookScalePatched; }
    /** True once the foreign-zoom key filters matched (Zoom package). */
    public static boolean keyFilterPatched() { return CameraTransformer.keyFilterPatched; }

    // -----------------------------------------------------------------------
    // Fullbright: EntityRenderer.updateLightmap gamma read
    // -----------------------------------------------------------------------

    /**
     * Spliced immediately after each {@code GETFIELD GameSettings.gammaSetting} in
     * {@code updateLightmap}: the raw gamma is on the stack, we return the boosted
     * value. When Fullbright is off this is the identity, so vanilla behaviour is
     * byte-for-byte unchanged.
     */
    public static float gamma(float v) {
        try {
            return FullbrightModule.active() ? (float) Math.max(v, FullbrightModule.level()) : v;
        } catch (Throwable t) {
            return v;
        }
    }

    // -----------------------------------------------------------------------
    // HandPosition + OldAnimations: first-person item render
    // -----------------------------------------------------------------------

    /**
     * The partial-tick value {@code renderItemInFirstPerson} was called with, captured
     * at the method head. {@link CombatHooks#liveSwing()} reads it so the
     * old-animation swing is frame-smooth (interpolated) rather than stepping at tick
     * rate. Defaults to 1.0 so a missed splice degrades to the previous behaviour.
     */
    public static volatile float firstPersonPartialTicks = 1.0F;

    /** Head splice: store the partial ticks for the frame-smooth swing. */
    public static void beginFirstPerson(float partialTicks) {
        try {
            firstPersonPartialTicks = partialTicks;
        } catch (Throwable t) {
            // never-throw
        }
    }

    /**
     * Spliced right after the first {@code GlStateManager.pushMatrix()} in
     * {@code renderItemInFirstPerson}: after vanilla's pitch/yaw sway and before the
     * arm/item transform, inside the push/pop so the offset can never leak. Only
     * translates when the module is on and the main-hand offset is non-zero.
     */
    public static void handOffset() {
        try {
            if (!HandPositionModule.active()) return;
            float[] o = HandPositionModule.mainOffset();
            if (o[0] != 0.0F || o[1] != 0.0F || o[2] != 0.0F) {
                GlStateManager.translate(o[0], o[1], o[2]);
            }
        } catch (Throwable t) {
            // never-throw
        }
    }

    // -----------------------------------------------------------------------
    // Zoom: mouse-look sensitivity scaling
    // -----------------------------------------------------------------------

    /**
     * Spliced right after the {@code I2F} that converts each {@code MouseHelper.deltaX/deltaY} read in
     * {@code EntityRenderer.updateCameraAndRender}: the float look delta is on the stack, we scale it by the
     * current zoom factor so the on-screen aim speed stays constant while zoomed (the Zoomify feel). Scaling
     * in float loses no precision and also feeds the smooth-camera branch. Identity when not zoomed, so vanilla
     * look behaviour is unchanged.
     *
     * <p>FAIR-PLAY: proportional scale of the player's OWN mouse input while the zoom key is held; it never
     * aims, never reads a target.
     */
    public static float scaleLook(float v) {
        try {
            double s = ZoomModule.lookScale();
            return s < 0.999 ? (float) (v * s) : v;
        } catch (Throwable t) {
            return v;
        }
    }

    // -----------------------------------------------------------------------
    // Zoom "Block other zoom": foreign camera-zoom key filters
    //
    // Spliced before each IRETURN of KeyBinding.isKeyDown/isPressed and the static
    // GameSettings.isKeyDown, so the value on the stack (the boolean the method was
    // about to return) is rewritten. Stack-neutral (boolean in, boolean out), no
    // branches added, so no stack-map frames are needed. Client thread only.
    // -----------------------------------------------------------------------

    /** Per-binding classification cache; replaces the reference mixin's {@code @Unique} byte. Client thread only. */
    private static final Map<KeyBinding, Boolean> FOREIGN_CACHE = new IdentityHashMap<KeyBinding, Boolean>();

    /** {@code KeyBinding.pressTime}, resolved lazily (MCP name in dev, SRG in prod), used to drain a blocked click. */
    private static volatile Field pressTimeField;
    private static volatile boolean pressTimeResolved;

    /**
     * Filter a "held" read ({@code KeyBinding.isKeyDown} / {@code GameSettings.isKeyDown}). When "Block other
     * zoom" is on and this binding is another mod's camera-zoom key, the read becomes {@code false} so that
     * mod's HOLD zoom never fires; otherwise the original value passes through untouched. Never blocks a key
     * that is not currently pressed, and never our own bindings ({@code s1mp1e} in the name).
     */
    public static boolean filterHeld(boolean pressed, KeyBinding kb) {
        try {
            if (!pressed || !ZoomModule.blocksForeignZoom()) return pressed;
            return !isForeign(kb);
        } catch (Throwable t) {
            return pressed;
        }
    }

    /**
     * Filter a fresh-click read ({@code KeyBinding.isPressed}, which drains one queued press). When "Block other
     * zoom" is on and this is a foreign camera-zoom key, the click is swallowed ({@code false}) and the whole
     * queued-press count is drained to zero so a later poll cannot resurrect it — the same drain the reference
     * mixin does with {@code timesPressed = 0}. Otherwise the value passes through.
     */
    public static boolean filterClick(boolean pressed, KeyBinding kb) {
        try {
            if (!pressed) return false;
            if (!blockClick(kb)) return true;
            drainPressTime(kb);
            return false;
        } catch (Throwable t) {
            return pressed;
        }
    }

    /** Whether a fresh click of this key binding should be swallowed (blocking on and the key is foreign). */
    public static boolean blockClick(KeyBinding kb) {
        try {
            return ZoomModule.blocksForeignZoom() && isForeign(kb);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Classify (and cache) whether this binding is another mod's CAMERA-zoom key. Failure means "never block". */
    private static boolean isForeign(KeyBinding kb) {
        if (kb == null) return false;
        Boolean cached = FOREIGN_CACHE.get(kb);
        if (cached != null) return cached.booleanValue();
        boolean foreign;
        try {
            foreign = ForeignZoomKeys.isCameraZoom(kb.getKeyDescription(), kb.getKeyCategory());
        } catch (Throwable t) {
            foreign = false;
        }
        FOREIGN_CACHE.put(kb, Boolean.valueOf(foreign));
        if (foreign) {
            try {
                System.out.println("[S1mp1e] Zoom: blocking foreign zoom key " + kb.getKeyDescription());
            } catch (Throwable ignored) {
                // logging must never break the input path
            }
        }
        return foreign;
    }

    /** Zero the queued press count so a swallowed click cannot be read as pressed on a later poll. */
    private static void drainPressTime(KeyBinding kb) {
        try {
            Field f = pressTimeField;
            if (f == null) {
                if (pressTimeResolved) return;
                f = resolvePressTime();
                pressTimeField = f;
                pressTimeResolved = true;
                if (f == null) return;
            }
            f.setInt(kb, 0);
        } catch (Throwable t) {
            // never-throw: at worst the queued press stays and vanilla returns it once more
        }
    }

    private static Field resolvePressTime() {
        String[] names = { "pressTime", "field_151474_i" };
        for (int i = 0; i < names.length; i++) {
            try {
                Field f = KeyBinding.class.getDeclaredField(names[i]);
                f.setAccessible(true);
                return f;
            } catch (Throwable ignored) {
                // try the next name
            }
        }
        return null;
    }
}
