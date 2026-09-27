package dev.s1mp1e.glass.render;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumerProvider;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 1.18.2 stand-in for the 1.20 {@code DrawContext.draw()} call that every raw-GL glass draw is preceded by.
 *
 * <p>Our glass renders through a standalone GL program, NOT through MC's buffered GUI consumers, so anything
 * the GUI has already queued (fills, text, item models) must be pushed to the framebuffer first, otherwise it
 * lands on top of the glass later instead of underneath it. On 1.18.2 that takes two steps:
 * <ol>
 *   <li>{@code MinecraftClient.getBufferBuilders().getEntityVertexConsumers().draw()} - vanilla's shared
 *       immediate (what 1.20's DrawContext wraps);</li>
 *   <li>ImmediatelyFast only: IF's HUD batching defers fills, textures and text into its OWN buffers, which step
 *       1 does not touch. While HUD batching is active we force IF's batch to draw now, under the current
 *       transform, so it lands under (before glass) / correctly scaled (with the items) rather than at IF's own
 *       end-of-HUD flush after our RenderSystem scale has been popped.</li>
 * </ol>
 *
 * <p><b>Two IF generations are supported, resolved lazily by reflection.</b> IF &gt;=1.2 (MC 1.20+) exposes a
 * public API package {@code net.raphimc.immediatelyfastapi} with {@code BatchingAccess.isHudBatching()} /
 * {@code forceDrawBuffers()}. IF 1.1.x (the line that ships for 1.18.2, e.g. the user's 1.1.12) predates that
 * package entirely - its batching lives in {@code net.raphimc.immediatelyfast.feature.batching.BatchingBuffers}
 * as static {@code isFillBatching()/isTextBatching()/isTextureBatching()} flags plus static
 * {@code FILL_CONSUMER/TEXT_CONSUMER/TEXTURE_CONSUMER} fields (each a {@link VertexConsumerProvider.Immediate}).
 * Force-drawing there means calling {@link VertexConsumerProvider.Immediate#draw()} on each active consumer -
 * exactly what IF's own {@code endFillBatching()} does (minus the end), i.e. a force-flush without ending the
 * batch. Without this fallback the &gt;=1.2 branch's {@code Class.forName} throws on 1.1.x and the whole IF
 * flush is silently dead, which mis-scales the glass hotbar's durability/cooldown overlay (a fill batched by IF
 * and drawn only at IF's end-of-HUD flush, after our model-view scale is gone).
 *
 * <p>IF is reached purely by reflection, resolved once and only when the mod is loaded, so there is no
 * compile-time or class-loading dependency on it. Any failure in the IF branch disables that branch for the
 * rest of the session (logged once); {@link #flush()} itself never throws.
 */
public final class GuiFlush {

    private GuiFlush() {}

    private static final String IF_MOD_ID = "immediatelyfast";

    // ImmediatelyFast >= 1.2 public API (MC 1.20+).
    private static final String IF_API = "net.raphimc.immediatelyfastapi.ImmediatelyFastApi";
    private static final String IF_API_ACCESS = "net.raphimc.immediatelyfastapi.ApiAccess";
    private static final String IF_BATCHING_ACCESS = "net.raphimc.immediatelyfastapi.BatchingAccess";

    // ImmediatelyFast 1.1.x internal batching (MC 1.18.2 line — no public API package).
    private static final String IF_BATCHING_BUFFERS = "net.raphimc.immediatelyfast.feature.batching.BatchingBuffers";

    /** getApiImpl() may still be null very early in start-up; give up after this many null results. */
    private static final int MAX_NULL_IMPL_TRIES = 600;

    /** 0 = not yet resolved, 1 = IF >=1.2 public API, 2 = IF 1.1.x internal batching. */
    private static int ifMode;
    private static boolean ifDisabled;     // true when IF is absent or the branch failed (permanent)
    private static int ifNullTries;

    // Mode 1 (>=1.2) handles.
    private static Object ifBatching;      // net.raphimc.immediatelyfastapi.BatchingAccess instance
    private static Method ifIsHudBatching;
    private static Method ifForceDrawBuffers;

    // Mode 2 (1.1.x) handles.
    private static Method ifIsFillBatching, ifIsTextBatching, ifIsTextureBatching;
    private static Field ifFillConsumer, ifTextConsumer, ifTextureConsumer;

    private static boolean vanillaWarned;

    /** Draw everything the GUI has buffered so far. Safe to call anywhere on the render thread; never throws. */
    public static void flush() {
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc != null) mc.getBufferBuilders().getEntityVertexConsumers().draw();
        } catch (Throwable t) {
            if (!vanillaWarned) {
                vanillaWarned = true;
                System.out.println("[S1mp1e] GUI buffer flush failed: " + t);
            }
        }
        flushImmediatelyFast();
    }

    private static void flushImmediatelyFast() {
        if (ifDisabled) return;
        try {
            if (ifMode == 0 && !resolveImmediatelyFast()) return;
            if (ifMode == 1) {
                if ((Boolean) ifIsHudBatching.invoke(ifBatching)) {
                    ifForceDrawBuffers.invoke(ifBatching);
                }
            } else if (ifMode == 2) {
                boolean any = (Boolean) ifIsFillBatching.invoke(null)
                           || (Boolean) ifIsTextBatching.invoke(null)
                           || (Boolean) ifIsTextureBatching.invoke(null);
                if (any) {
                    forceDraw(ifFillConsumer);
                    forceDraw(ifTextConsumer);
                    forceDraw(ifTextureConsumer);
                }
            }
        } catch (Throwable t) {
            disableImmediatelyFast("ImmediatelyFast batch flush failed, IF flush disabled: " + t);
        }
    }

    /** Flush one of IF 1.1.x's static batch consumers if it currently holds an active {@link VertexConsumerProvider.Immediate}. */
    private static void forceDraw(Field consumerField) throws Exception {
        Object c = consumerField.get(null);          // null between begin/end of that batch kind
        if (c instanceof VertexConsumerProvider.Immediate imm) imm.draw();
    }

    /** @return true when an IF branch is ready; false when it is disabled or not yet available. */
    private static boolean resolveImmediatelyFast() throws Exception {
        if (!FabricLoader.getInstance().isModLoaded(IF_MOD_ID)) {
            ifDisabled = true;          // silent: IF simply is not installed
            return false;
        }
        ClassLoader cl = GuiFlush.class.getClassLoader();
        // Prefer the >=1.2 public API; fall back to the 1.1.x internal batching when that package is absent.
        Class<?> api = tryClass(IF_API, cl);
        return (api != null) ? resolveApi(api, cl) : resolveInternal(cl);
    }

    /** Resolve IF >=1.2's public {@code immediatelyfastapi} batching access. */
    private static boolean resolveApi(Class<?> api, ClassLoader cl) throws Exception {
        Class<?> apiAccess = Class.forName(IF_API_ACCESS, true, cl);
        Class<?> batchingAccess = Class.forName(IF_BATCHING_ACCESS, true, cl);

        Object impl = api.getMethod("getApiImpl").invoke(null);
        if (impl == null) {
            // IF has not published its API yet; try again on a later flush, but not forever.
            if (++ifNullTries >= MAX_NULL_IMPL_TRIES) {
                disableImmediatelyFast("ImmediatelyFast API never became available, IF flush disabled");
            }
            return false;
        }
        Object batching = apiAccess.getMethod("getBatching").invoke(impl);
        if (batching == null) {
            disableImmediatelyFast("ImmediatelyFast returned no BatchingAccess, IF flush disabled");
            return false;
        }
        // Look the methods up on the public API interface (not the impl class) so invoke() needs no access tricks.
        ifIsHudBatching = batchingAccess.getMethod("isHudBatching");
        ifForceDrawBuffers = batchingAccess.getMethod("forceDrawBuffers");
        ifBatching = batching;
        ifMode = 1;
        System.out.println("[S1mp1e] ImmediatelyFast (>=1.2 API) detected: glass draws flush its HUD batch first");
        return true;
    }

    /** Resolve IF 1.1.x's internal {@code BatchingBuffers} force-flush handles (no public API on that line). */
    private static boolean resolveInternal(ClassLoader cl) throws Exception {
        Class<?> bb = tryClass(IF_BATCHING_BUFFERS, cl);
        if (bb == null) {
            // IF is loaded but neither the >=1.2 API nor the known 1.1.x internals are present: unknown build.
            disableImmediatelyFast("ImmediatelyFast present but no known batching API, IF flush disabled");
            return false;
        }
        ifIsFillBatching = bb.getMethod("isFillBatching");
        ifIsTextBatching = bb.getMethod("isTextBatching");
        ifIsTextureBatching = bb.getMethod("isTextureBatching");
        ifFillConsumer = bb.getField("FILL_CONSUMER");
        ifTextConsumer = bb.getField("TEXT_CONSUMER");
        ifTextureConsumer = bb.getField("TEXTURE_CONSUMER");
        ifMode = 2;
        System.out.println("[S1mp1e] ImmediatelyFast (1.1.x) detected: glass draws flush its HUD batch first");
        return true;
    }

    /** Class.forName that returns null (instead of throwing) when the class is simply not present. */
    private static Class<?> tryClass(String name, ClassLoader cl) {
        try {
            return Class.forName(name, false, cl);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private static void disableImmediatelyFast(String why) {
        ifDisabled = true;
        ifMode = 0;
        ifBatching = null;
        ifIsHudBatching = null;
        ifForceDrawBuffers = null;
        ifIsFillBatching = ifIsTextBatching = ifIsTextureBatching = null;
        ifFillConsumer = ifTextConsumer = ifTextureConsumer = null;
        System.out.println("[S1mp1e] " + why);
    }
}
