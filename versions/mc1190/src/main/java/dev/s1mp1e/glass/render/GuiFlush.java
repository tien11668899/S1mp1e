package dev.s1mp1e.glass.render;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.lang.reflect.Method;

/**
 * 1.19.2 stand-in for the 1.20 {@code DrawContext.draw()} call that every raw-GL glass draw is preceded by.
 *
 * <p>Our glass renders through a standalone GL program, NOT through MC's buffered GUI consumers, so anything
 * the GUI has already queued (fills, text, item models) must be pushed to the framebuffer first, otherwise it
 * lands on top of the glass later instead of underneath it. On 1.19.2 that takes two steps:
 * <ol>
 *   <li>{@code MinecraftClient.getBufferBuilders().getEntityVertexConsumers().draw()} - vanilla's shared
 *       immediate (what 1.20's DrawContext wraps);</li>
 *   <li>ImmediatelyFast only: IF's HUD batching (begun at HEAD of renderHotbar / renderStatusBars /
 *       renderExperienceBar / ... and ended on their return) defers fills, textures, text and item models into
 *       its OWN buffers, which step 1 does not touch. While HUD batching is active we call IF's public
 *       {@code BatchingAccess.forceDrawBuffers()} so that batch is drawn now, under the current transform.</li>
 * </ol>
 *
 * <p>IF is reached purely by reflection, resolved lazily once and only when the mod is loaded, so there is no
 * compile-time or class-loading dependency on it. Any failure in the IF branch disables that branch for the
 * rest of the session (logged once); {@link #flush()} itself never throws.
 */
public final class GuiFlush {

    private GuiFlush() {}

    private static final String IF_MOD_ID = "immediatelyfast";
    private static final String IF_API = "net.raphimc.immediatelyfastapi.ImmediatelyFastApi";
    private static final String IF_API_ACCESS = "net.raphimc.immediatelyfastapi.ApiAccess";
    private static final String IF_BATCHING_ACCESS = "net.raphimc.immediatelyfastapi.BatchingAccess";

    /** getApiImpl() may still be null very early in start-up; give up after this many null results. */
    private static final int MAX_NULL_IMPL_TRIES = 600;

    private static boolean ifResolved;     // true once the IF branch is ready to use
    private static boolean ifDisabled;     // true when IF is absent or the branch failed (permanent)
    private static int ifNullTries;
    private static Object ifBatching;      // net.raphimc.immediatelyfastapi.BatchingAccess instance
    private static Method ifIsHudBatching;
    private static Method ifForceDrawBuffers;

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
            if (!ifResolved && !resolveImmediatelyFast()) return;
            if ((Boolean) ifIsHudBatching.invoke(ifBatching)) {
                ifForceDrawBuffers.invoke(ifBatching);
            }
        } catch (Throwable t) {
            disableImmediatelyFast("ImmediatelyFast batch flush failed, IF flush disabled: " + t);
        }
    }

    /** @return true when the IF branch is ready; false when it is disabled or not yet available. */
    private static boolean resolveImmediatelyFast() throws Exception {
        if (!FabricLoader.getInstance().isModLoaded(IF_MOD_ID)) {
            ifDisabled = true;          // silent: IF simply is not installed
            return false;
        }
        ClassLoader cl = GuiFlush.class.getClassLoader();
        Class<?> api = Class.forName(IF_API, true, cl);
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
        Method isHud = batchingAccess.getMethod("isHudBatching");
        Method force = batchingAccess.getMethod("forceDrawBuffers");

        ifBatching = batching;
        ifIsHudBatching = isHud;
        ifForceDrawBuffers = force;
        ifResolved = true;
        System.out.println("[S1mp1e] ImmediatelyFast detected: glass draws flush its HUD batch first");
        return true;
    }

    private static void disableImmediatelyFast(String why) {
        ifDisabled = true;
        ifResolved = false;
        ifBatching = null;
        ifIsHudBatching = null;
        ifForceDrawBuffers = null;
        System.out.println("[S1mp1e] " + why);
    }
}
