package dev.s1mp1e.client.knife;

import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;

import java.util.Locale;
import java.util.concurrent.Callable;

/**
 * Where the skin compositors' raw OpenGL runs. On the OpenGL backend that is the game's own context. On Vulkan there is
 * no OpenGL context at all (LWJGL aborts the JVM on the first GL call), so a hidden 16x16 GLFW window with an OpenGL 3.3
 * core context is made once and made current just for the composite; the compositors read their result back into a
 * NativeImage, which the game then uploads through its own backend, so the skins look the same either way.
 * Render (= main) thread only.
 *
 * <p>GLFW reports events through per-window callbacks and Minecraft only sets them on its own window, so this window's
 * resize events never reach the game (26.3's SDL event queue did not tell windows apart; see its CompositorContext).
 */
final class CompositorContext {
    private static int gameGl = -1;   // -1 unknown, 0 no, 1 yes
    private static long window;
    private static GLCapabilities caps;

    private CompositorContext() {}

    private static boolean gameIsOpenGl() {
        if (gameGl < 0) {
            String b = RenderSystem.getDevice().getDeviceInfo().backendName();
            gameGl = b == null || b.toLowerCase(Locale.ROOT).contains("opengl") ? 1 : 0;
        }
        return gameGl == 1;
    }

    static <T> T call(Callable<T> job) throws Exception {
        if (gameIsOpenGl()) return job.call();
        long prev = GLFW.glfwGetCurrentContext();
        if (window == 0L) {
            GLFW.glfwDefaultWindowHints();
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_FOCUSED, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_FOCUS_ON_SHOW, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_OPENGL_API);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
            long w = GLFW.glfwCreateWindow(16, 16, "S1mp1e skin compositor", 0L, 0L);
            GLFW.glfwDefaultWindowHints();
            if (w == 0L) throw new IllegalStateException("no window for the skin compositor's OpenGL context");
            GLFW.glfwMakeContextCurrent(w);
            if (GL.getFunctionProvider() == null) GL.create();
            caps = GL.createCapabilities();
            window = w;
        } else {
            GLFW.glfwMakeContextCurrent(window);
            GL.setCapabilities(caps);
        }
        try {
            return job.call();
        } finally {
            GLFW.glfwMakeContextCurrent(prev);
            GL.setCapabilities(null);
        }
    }
}
