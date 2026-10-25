package dev.s1mp1e.client.knife;

import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.sdl.SDLVideo;

import java.util.Locale;
import java.util.concurrent.Callable;

/**
 * Where the skin compositors' raw OpenGL runs. On the OpenGL backend that is the game's own context. On Vulkan there is
 * no OpenGL context at all (LWJGL aborts the JVM on the first GL call), so a hidden 16x16 SDL window with an OpenGL 3.3
 * core context is made once and made current just for the composite; the compositors read their result back into a
 * NativeImage, which the game then uploads through its own backend, so the skins look the same either way.
 * Render (= main) thread only.
 *
 * <p>26.3's Window.handleEvent applies every SDL window event to the game window without looking at the window ID, so
 * the hidden window's own resize events (Windows grows a 16x16 captioned window to its minimum size) shrank the game's
 * framebuffer the first time a knife was composited; CompositorWindowEventMixin drops events from {@link #windowId}.
 */
public final class CompositorContext {
    private static int gameGl = -1;   // -1 unknown, 0 no, 1 yes
    private static long window;
    private static long context;
    private static GLCapabilities caps;
    private static volatile int windowId;

    private CompositorContext() {}

    /** SDL window ID of the hidden compositor window; 0 = none made. */
    public static int windowId() {
        return windowId;
    }

    private static boolean gameIsOpenGl() {
        if (gameGl < 0) {
            String b = RenderSystem.getDevice().getDeviceInfo().backendName();
            gameGl = b == null || b.toLowerCase(Locale.ROOT).contains("opengl") ? 1 : 0;
        }
        return gameGl == 1;
    }

    static <T> T call(Callable<T> job) throws Exception {
        if (gameIsOpenGl()) return job.call();
        long prevWindow = SDLVideo.SDL_GL_GetCurrentWindow();
        long prevContext = SDLVideo.SDL_GL_GetCurrentContext();
        if (context == 0L) {
            SDLVideo.SDL_GL_ResetAttributes();
            SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_MAJOR_VERSION, 3);
            SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_MINOR_VERSION, 3);
            SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_PROFILE_MASK, SDLVideo.SDL_GL_CONTEXT_PROFILE_CORE);
            long w = SDLVideo.SDL_CreateWindow("S1mp1e skin compositor", 16, 16, SDLVideo.SDL_WINDOW_OPENGL | SDLVideo.SDL_WINDOW_HIDDEN);
            if (w == 0L) throw new IllegalStateException("no window for the skin compositor's OpenGL context");
            windowId = SDLVideo.SDL_GetWindowID(w);
            long c = SDLVideo.SDL_GL_CreateContext(w);
            if (c == 0L) throw new IllegalStateException("no OpenGL context for the skin compositor");
            SDLVideo.SDL_GL_MakeCurrent(w, c);
            if (GL.getFunctionProvider() == null) GL.create(SDLVideo::SDL_GL_GetProcAddress);
            caps = GL.createCapabilities();
            window = w;
            context = c;
        } else {
            SDLVideo.SDL_GL_MakeCurrent(window, context);
            GL.setCapabilities(caps);
        }
        try {
            return job.call();
        } finally {
            SDLVideo.SDL_GL_MakeCurrent(prevWindow, prevContext);
            GL.setCapabilities(null);
        }
    }
}
