package dev.s1mp1e.client.asm;

import dev.s1mp1e.client.module.ForeignZoomKeys;
import dev.s1mp1e.client.module.FullbrightModule;
import dev.s1mp1e.client.module.HandPositionModule;
import dev.s1mp1e.client.module.ZoomModule;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.EnumHand;

import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Static hook targets called from bytecode spliced by {@link CameraTransformer}.
 *
 * <p><b>These methods run in the render / input hot path and must never throw.</b> A
 * hook that raised out of {@code EntityRenderer.updateLightmap}, {@code KeyBinding.isKeyDown}
 * or {@code ItemRenderer.renderItemInFirstPerson} would crash the frame from inside MC
 * code we cannot try/catch, so every body here is wrapped and degrades to the vanilla
 * value on any failure.
 *
 * <p><b>FAIR-PLAY:</b> every method is render / own-input only. Nothing here reads a
 * targeted entity, a hit result, a distance, or any packet. {@link #gamma} raises
 * lightmap brightness (not x-ray); {@link #handOffset} nudges the drawn hand matrix;
 * {@link #scaleLook} scales only the player's own mouse delta while the zoom key is held;
 * the two key filters only make OTHER mods' camera-zoom keys read unpressed.
 */
public final class CameraHooks {

    private CameraHooks() {}

    // ---- per-patch success flags -------------------------------------------
    // The flags themselves live on CameraTransformer (writing a static field here
    // would class-load this hook DURING the transform of KeyBinding/GameSettings/
    // EntityRenderer). These are read-only views for runtime callers.

    /** True once the {@code EntityRenderer.updateLightmap} gamma splice matched at least once. */
    public static boolean gammaPatched()     { return CameraTransformer.gammaPatched; }
    /** True once the per-hand {@code ItemRenderer.renderItemInFirstPerson} offset splice matched. */
    public static boolean handPatched()      { return CameraTransformer.handPatched; }
    /** True once the mouse-look scaling splice matched. */
    public static boolean lookScalePatched() { return CameraTransformer.lookScalePatched; }
    /** True once the foreign-zoom key filters matched. */
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
    // HandPosition: per-hand first-person item render
    // -----------------------------------------------------------------------

    /**
     * Spliced right after the first {@code GlStateManager.pushMatrix()} of the per-hand
     * {@code ItemRenderer.renderItemInFirstPerson(AbstractClientPlayer,F,F,EnumHand,F,ItemStack,F)}
     * with that call's {@code EnumHand} argument: translates by the main-hand or the off-hand
     * offset INSIDE vanilla's own per-hand push/pop, so the two hands are independent and an
     * offset can never leak into the other hand or the rest of the frame. No-op when the module
     * is off or the offset is zero.
     */
    public static void handOffset(EnumHand hand) {
        try {
            if (!HandPositionModule.active()) return;
            float[] o = hand == EnumHand.OFF_HAND ? HandPositionModule.offOffset() : HandPositionModule.mainOffset();
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
     * that is not currently pressed, and never our own bindings ({@code s1mp1e} in the name). Nothing is
     * written to the binding or to options.txt.
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

    // ---- the static GameSettings.isKeyDown(KeyBinding) funnel ------------------
    //
    // OptiFine's zoom polls the STATIC GameSettings.isKeyDown, so that return value is
    // wrapped too — but unlike the two KeyBinding methods, its key cannot be read at the
    // return. MEASURED, not assumed: in the SRG form of GameSettings the frame covering
    // those returns gives local 0 the type TOP (the parameter is dead by then), so the
    // ALOAD 0 the spec sketches is a hard "VerifyError: Bad local variable type" that kills
    // GameSettings at class load. At METHOD ENTRY the locals are the descriptor by
    // definition, so the key is captured there and the return hook takes only the boolean.
    //
    // One slot is enough: the method does not call itself (its body asks the KeyBinding,
    // which never routes back here), and the read clears the slot, so a stale key can never
    // be reused — a missing capture degrades to "pass the value through unchanged".

    /** Client thread only; see the note above. */
    private static KeyBinding pendingKey;

    /** Spliced at the head of {@code GameSettings.isKeyDown(KeyBinding)}: capture the argument. */
    public static void beginKeyQuery(KeyBinding kb) {
        try {
            pendingKey = kb;
        } catch (Throwable t) {
            // never-throw
        }
    }

    /**
     * Spliced before each {@code IRETURN} of {@code GameSettings.isKeyDown(KeyBinding)}: the boolean
     * the method was about to return is on the stack and we rewrite it, using the key
     * {@link #beginKeyQuery} captured for this invocation. With no captured key (a return path the
     * head splice could not reach) the value passes through untouched.
     */
    public static boolean filterHeldCurrent(boolean pressed) {
        try {
            KeyBinding kb = pendingKey;
            pendingKey = null;                       // consume: never classify against a stale key
            if (kb == null) return pressed;
            return filterHeld(pressed, kb);
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
                if (f.getType() != int.class) continue;
                f.setAccessible(true);
                return f;
            } catch (Throwable ignored) {
                // try the next name
            }
        }
        return null;
    }
}
