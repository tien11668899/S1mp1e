package dev.s1mp1e.client.mixin;

import com.mojang.blaze3d.platform.Window;
import dev.s1mp1e.client.knife.CompositorContext;
import org.lwjgl.sdl.SDL_Event;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * On Vulkan the knife and glove skins are composited in a hidden SDL window with its own OpenGL context
 * (CompositorContext). 26.3's Window.handleEvent takes every window event as the game window's — resize, pixel size,
 * close, minimize, fullscreen — so that window's resize events shrank the game's framebuffer while the window stayed
 * fullscreen. Window events (SDL_EVENT_WINDOW_SHOWN 0x202 up to 0x2FF) from the compositor window are dropped here.
 */
@Mixin(Window.class)
public abstract class CompositorWindowEventMixin {
    @Unique private static int s1mp1e$logged;

    @Inject(method = "handleEvent", at = @At("HEAD"), cancellable = true, require = 0)
    private void s1mp1e$ignoreCompositorWindow(SDL_Event event, CallbackInfo ci) {
        int id = CompositorContext.windowId();
        if (id == 0) return;
        int type = event.type();
        if (type < 0x202 || type > 0x2FF || event.window().windowID() != id) return;
        if (s1mp1e$logged < 8) {
            s1mp1e$logged++;
            System.out.printf("[S1mp1e] skin compositor window event kept off the game window: 0x%x %dx%d%n",
                type, event.window().data1(), event.window().data2());
        }
        ci.cancel();
    }
}
