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
 * System 1 — screen open/close cross-dissolve. 1.13.2 (Legacy Fabric) port of the
 * 1.16.5 mixin of the same name. Nothing about the animation changes; only the
 * plumbing does — the SAME {@link ScreenFade} core (already ported to 1.13.2) is
 * called in the SAME order, so the dissolve is byte-for-byte identical:
 *
 * <ul>
 *   <li>the screen change only STARTS the dissolve ({@link ScreenFade#trigger()}),
 *       it never captures;</li>
 *   <li>every rendered frame draws the outgoing snapshot then captures the
 *       finished frame — {@link ScreenFade#draw()} BEFORE
 *       {@link ScreenFade#captureFrame()};</li>
 *   <li>capture pauses for the duration of a dissolve (inside {@code ScreenFade}).</li>
 * </ul>
 *
 * <p><b>DRAW half, screen branch.</b> Fabric target {@code Screen.render}. In 1.13.2
 * (pre-MatrixStack) its descriptor is {@code (IIF)V} — {@code (mouseX, mouseY, delta)}
 * — intermediary {@code method_1025}, named {@code render}. {@code @At("TAIL")}
 * lands at the end of whatever screen is on top. {@code ScreenFade} paints in raw GL
 * exactly like the Forge line, so the geometry is unchanged.
 *
 * <p>The STARTER half lives below as {@link ScreenFadeOpenMixin} (a second single-
 * target mixin, NOT registered in {@code s1mp1e.mixins.json} — the live starter is
 * {@link MinecraftClientFadeMixin}); kept here byte-for-byte with the 1.16.5 file so
 * the two lines diff cleanly.
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
 * System 1 — STARTER half (not registered; see {@link MinecraftClientFadeMixin}).
 * 1.13.2 target {@code MinecraftClient.setScreen(Screen)} ({@code method_2928}) at
 * {@code @At("HEAD")}, where {@code currentScreen} ({@code field_3816}) still holds
 * the OUTGOING screen.
 */
@Mixin(MinecraftClient.class)
abstract class ScreenFadeOpenMixin {

    @Shadow public Screen currentScreen;

    @Inject(method = "setScreen", at = @At("HEAD"))
    private void s1mp1e$fadeOnScreenChange(Screen screen, CallbackInfo ci) {
        // resize re-sets the identical instance -> no dissolve (matches Forge)
        if (this.currentScreen == screen) return;
        ScreenFade.trigger();
    }
}
