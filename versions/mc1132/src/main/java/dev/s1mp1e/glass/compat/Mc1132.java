package dev.s1mp1e.glass.compat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.class_4112;
import net.minecraft.class_4117;
import net.minecraft.world.GameMode;

import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * The single place that touches the unmapped Legacy-yarn 1.13.2 Window / Mouse / focus /
 * item-renderer names. Every other class in the client and glass layers goes through this
 * bridge instead of hard-coding {@code class_4117}/{@code class_4112}/{@code method_18xxx},
 * so a mappings surprise is fixed in exactly one file.
 *
 * <p>This class lives OUTSIDE the mixin package on purpose: a mixin class may not be
 * referenced from normal code, so the bridge cannot live there.
 *
 * <p>Every name below was javap-verified against
 * {@code net.legacyfabric:yarn:1.13.2+build.604-v2}. Only JDK, LWJGL and Minecraft classes
 * are imported.
 *
 * <p><b>Null-safety contract:</b> every getter tolerates a null {@link #mc()},
 * {@link #window()}, {@code options}, {@code interactionManager} or {@code field_19945}
 * and returns a neutral value (0 / false / null). The client entrypoint and the preload
 * run at the HEAD of {@code initializeGame}, BEFORE the Window, GameOptions, the
 * ResourceManager and the GL context exist ({@link #window()} is null then), so nothing
 * on the init path may assume a Window. The GL fallbacks in {@link #fbW()}/{@link #fbH()}/
 * {@link #scaledW()}/{@link #scaledH()} are only reached from render paths, where a GL
 * context is guaranteed.
 *
 * <p>Uses only LWJGL entry points present in BOTH 3.1.6 (compile / production) and 3.3.2
 * (dev {@code runClient}): {@code glfwGetKey}, {@code glfwGetMouseButton},
 * {@code glGetIntegerv}, {@code glGetFloatv} and their constants.
 */
public final class Mc1132 {
    private Mc1132() {}

    // Scratch buffers for the GL fallbacks; render-thread confined (only the render thread
    // reaches the fallback branches, and it is single-threaded for GL).
    private static final IntBuffer S1MP1E_VP = BufferUtils.createIntBuffer(16);
    private static final FloatBuffer S1MP1E_PROJ = BufferUtils.createFloatBuffer(16);

    /** {@code MinecraftClient.getInstance()} (may be null very early / in odd tooling). */
    public static MinecraftClient mc() {
        return MinecraftClient.getInstance();
    }

    /**
     * The window, {@code net.minecraft.class_4117}, from the PUBLIC field
     * {@code MinecraftClient.field_19944}. It is null until {@code initializeGame} creates
     * it, and the Fabric client entrypoint + preload run BEFORE that, so callers must
     * tolerate null.
     */
    public static class_4117 window() {
        MinecraftClient m = mc();
        return m == null ? null : m.field_19944;
    }

    /** The GLFW window handle ({@code window().method_18315()}), or 0 when no window yet. */
    public static long handle() {
        class_4117 w = window();
        return w == null ? 0L : w.method_18315();
    }

    /** Scaled GUI width — {@code method_18321()}, exactly what {@code InGameHud.render} reads. */
    public static int scaledW() {
        class_4117 w = window();
        if (w != null) return w.method_18321();
        return (int) s1mp1e$scaledWFromProj();
    }

    /** Scaled GUI height — {@code method_18322()}. */
    public static int scaledH() {
        class_4117 w = window();
        if (w != null) return w.method_18322();
        return (int) s1mp1e$scaledHFromProj();
    }

    /** Framebuffer width — {@code method_18317()} (glfwGetFramebufferSize / fb callback). */
    public static int fbW() {
        class_4117 w = window();
        if (w != null) return w.method_18317();
        return s1mp1e$vp(2);
    }

    /** Framebuffer height — {@code method_18318()}. */
    public static int fbH() {
        class_4117 w = window();
        if (w != null) return w.method_18318();
        return s1mp1e$vp(3);
    }

    /** Window width — {@code method_18319()} (window-size callback; the space the cursor lives in). */
    public static int winW() {
        class_4117 w = window();
        return w == null ? 0 : w.method_18319();
    }

    /** Window height — {@code method_18320()}. */
    public static int winH() {
        class_4117 w = window();
        return w == null ? 0 : w.method_18320();
    }

    /** GUI scale factor — {@code method_18325()} (double). */
    public static double scaleFactor() {
        class_4117 w = window();
        return w == null ? 1.0 : w.method_18325();
    }

    /** Raw cursor X in window pixels — {@code MinecraftClient.field_19945.method_18249()}. */
    public static double mouseX() {
        MinecraftClient m = mc();
        if (m == null) return 0.0;
        class_4112 mouse = m.field_19945;
        return mouse == null ? 0.0 : mouse.method_18249();
    }

    /** Raw cursor Y in window pixels — {@code method_18250()}. */
    public static double mouseY() {
        MinecraftClient m = mc();
        if (m == null) return 0.0;
        class_4112 mouse = m.field_19945;
        return mouse == null ? 0.0 : mouse.method_18250();
    }

    /**
     * Cursor X in scaled-GUI coordinates: {@code raw * scaledW / max(1, winW)}. This is
     * exactly {@code Mouse.onMouseButton}'s own mapping (verified at {@code method_18242}).
     */
    public static double scaledMouseX() {
        int win = winW();
        return mouseX() * scaledW() / Math.max(1, win);
    }

    /** Cursor Y in scaled-GUI coordinates: {@code raw * scaledH / max(1, winH)}. */
    public static double scaledMouseY() {
        int win = winH();
        return mouseY() * scaledH() / Math.max(1, win);
    }

    /**
     * <b>Legacy-yarn MISNOMER.</b> {@code MinecraftClient.isFullscreen()} does NOT report
     * fullscreen: it returns {@code field_19934}, the GLFW window-FOCUS flag written by the
     * {@code class_4117.method_18297} focus callback. Named {@code focused()} here so call
     * sites are honest.
     */
    public static boolean focused() {
        MinecraftClient m = mc();
        return m != null && m.isFullscreen();
    }

    /**
     * True while the given GLFW key code is held. Guards the valid GLFW key range
     * [32, 348] so codes GLFW does not define never raise a GLFW error. GLFW codes are
     * used natively on 1.13.2 (LWJGL3).
     */
    public static boolean keyDown(int glfw) {
        long h = handle();
        return h != 0L && glfw >= 32 && glfw <= 348
                && GLFW.glfwGetKey(h, glfw) == GLFW.GLFW_PRESS;
    }

    /** True while the given GLFW mouse button is held. */
    public static boolean mouseDown(int button) {
        long h = handle();
        return h != 0L && GLFW.glfwGetMouseButton(h, button) == GLFW.GLFW_PRESS;
    }

    /**
     * The GUI item renderer. <b>Legacy-yarn MISNOMER:</b> the GUI {@code ItemRenderer} is
     * {@code net.minecraft.client.render.item.HeldItemRenderer}, reached via
     * {@code MinecraftClient.getHeldItemRenderer()}. (The first-person renderer is the
     * separate {@code class_4225}, {@code method_18201()}.)
     */
    public static HeldItemRenderer itemRenderer() {
        MinecraftClient m = mc();
        return m == null ? null : m.getHeldItemRenderer();
    }

    /** F1 HUD-hidden flag — {@code GameOptions.field_19987}; false when options not yet built. */
    public static boolean hudHidden() {
        MinecraftClient m = mc();
        if (m == null) return false;
        GameOptions o = m.options;
        return o != null && o.field_19987;
    }

    /** Current game mode via {@code interactionManager.method_9667()}, or null if not in a world. */
    public static GameMode gameMode() {
        MinecraftClient m = mc();
        if (m == null || m.interactionManager == null) return null;
        return m.interactionManager.method_9667();
    }

    // --- GL fallbacks (render-path only; a GL context exists) --------------------------

    private static int s1mp1e$vp(int i) {
        S1MP1E_VP.clear();
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, S1MP1E_VP);
        return S1MP1E_VP.get(i);
    }

    // MC's GUI pass sets glOrtho(0, scaledW, scaledH, 0, ...), whose matrix has
    // m[0] = 2/scaledW and m[5] = -2/scaledH -> invert to recover the scaled size. Falls
    // back to the viewport when the projection is not that ortho.
    private static float s1mp1e$scaledWFromProj() {
        S1MP1E_PROJ.clear();
        GL11.glGetFloatv(GL11.GL_PROJECTION_MATRIX, S1MP1E_PROJ);
        float m0 = S1MP1E_PROJ.get(0);
        return (Math.abs(m0) > 1e-6f) ? Math.abs(2f / m0) : s1mp1e$vp(2);
    }

    private static float s1mp1e$scaledHFromProj() {
        S1MP1E_PROJ.clear();
        GL11.glGetFloatv(GL11.GL_PROJECTION_MATRIX, S1MP1E_PROJ);
        float m5 = S1MP1E_PROJ.get(5);
        return (Math.abs(m5) > 1e-6f) ? Math.abs(2f / m5) : s1mp1e$vp(3);
    }
}
