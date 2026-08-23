package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.ScreenFade;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * System 1 — screen open/close cross-dissolve. 1.14.4 tier port of the 1.16.5
 * mixin. Nothing about the animation changes; only the plumbing does. Same
 * {@link ScreenFade} core, same call order, so the dissolve is byte-for-byte
 * identical:
 *
 * <ul>
 *   <li>the screen change only STARTS the dissolve ({@link ScreenFade#trigger()});</li>
 *   <li>every rendered frame draws the outgoing snapshot then captures the finished
 *       frame — {@link ScreenFade#draw()} BEFORE {@link ScreenFade#captureFrame()};</li>
 *   <li>capture pauses for the duration of a dissolve (inside {@code ScreenFade}).</li>
 * </ul>
 *
 * <p>Three Forge handlers split across three MC targets:
 * <ul>
 *   <li>{@code onGuiOpen} (STARTER) &rarr; {@link ScreenFadeOpenMixin} on
 *       {@code MinecraftClient.openScreen} (also covered by
 *       {@link MinecraftClientFadeMixin}, the registered one);</li>
 *   <li>{@code onScreenPost} (DRAW, screen branch) &rarr; this class on
 *       {@code Screen.render};</li>
 *   <li>{@code onOverlayPost} (DRAW, in-world branch) &rarr; the render-TAIL added
 *       to {@code InGameHudMixin} / {@link InGameHudFadeMixin} (guarded by
 *       {@code currentScreen == null}).</li>
 * </ul>
 *
 * <p><b>DRAW half, screen branch.</b> Fabric target
 * {@code Screen.render(int, int, float)} — verified in the 1.14.4 yarn tiny as
 * {@code render(IIF)V} (class_437). 1.14.4 predates MatrixStack (1.15) and
 * RenderSystem (1.15), so the mandatory MatrixStack first arg of the 1.16.5 target
 * is simply absent from the descriptor and dropped from the handler; {@code ScreenFade}
 * paints in raw GL regardless, so the geometry is unchanged.
 */
@Mixin(Screen.class)
public abstract class ScreenFadeMixin {

    // Screen open: dissolve above the screen, then snapshot the finished frame.
    // Same order as Forge onScreenPost: draw() then captureFrame().
    @Inject(method = "render", at = @At("TAIL"))
    private void s1mp1e$fadeDrawScreen(int mouseX, int mouseY, float delta, CallbackInfo ci) {
        ScreenFade.draw();
        ScreenFade.captureFrame();
    }
}

/**
 * System 1 — STARTER half (redundant with {@link MinecraftClientFadeMixin}, which
 * is the registered one; kept for structural parity with the 1.16.5 source). Forge
 * counterpart {@code GlassScreenFadeHandler#onGuiOpen}. Fabric target
 * {@code MinecraftClient.openScreen(Screen)} ({@code openScreen(Ldcl;)V}); at HEAD
 * {@code currentScreen} still holds the OUTGOING screen.
 */
@Mixin(MinecraftClient.class)
abstract class ScreenFadeOpenMixin {

    @Shadow public Screen currentScreen;

    @Inject(method = "openScreen", at = @At("HEAD"))
    private void s1mp1e$fadeOnScreenChange(Screen screen, CallbackInfo ci) {
        // resize re-sets the identical instance -> no dissolve (matches Forge)
        if (this.currentScreen == screen) return;
        ScreenFade.trigger();
    }
}
