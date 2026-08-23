package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.ScreenFade;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * System 1 — screen open/close cross-dissolve. The Fabric 1.17.1 port of Forge
 * {@code GlassScreenFadeHandler}. Nothing about the animation changes; only the
 * plumbing does — {@code @SubscribeEvent} handlers become {@code @Inject}
 * mixins that call the SAME {@link ScreenFade} core, in the SAME order.
 *
 * <ul>
 *   <li>the screen change only STARTS the dissolve ({@link ScreenFade#trigger()}),
 *       it never captures — capture from outside the render pass grabbed an
 *       unfilled texture and flashed white;</li>
 *   <li>every rendered frame draws the outgoing snapshot then captures the
 *       finished frame — {@link ScreenFade#draw()} BEFORE
 *       {@link ScreenFade#captureFrame()};</li>
 *   <li>capture pauses for the duration of a dissolve (handled inside
 *       {@code ScreenFade}) so the fade is never snapshotted into itself.</li>
 * </ul>
 *
 * <p>The three Forge handlers split across three MC targets, so they cannot live
 * under a single {@code @Mixin}. They are grouped in this one file as separate
 * single-target mixin classes:
 * <ul>
 *   <li>{@code onGuiOpen} (STARTER) &rarr; {@link ScreenFadeOpenMixin} on
 *       {@code MinecraftClient.setScreen} (note: NOT registered in the config on
 *       its own; the registered STARTER is {@link MinecraftClientFadeMixin}, which
 *       is byte-for-byte identical. This inner class is kept only as the documented
 *       counterpart and is inert unless separately listed);</li>
 *   <li>{@code onScreenPost} (DRAW, screen branch) &rarr; this class on
 *       {@code Screen.render};</li>
 *   <li>{@code onOverlayPost} (DRAW, in-world branch) &rarr; the render-TAIL added
 *       to {@code InGameHudMixin} / {@link InGameHudFadeMixin} (guarded by
 *       {@code currentScreen == null}).</li>
 * </ul>
 *
 * <p><b>DRAW half, screen branch.</b> Over a newly opened screen, draw the outgoing
 * frame then snapshot the finished one. Fabric target
 * {@code Screen.render(MatrixStack, int, int, float)} at {@code @At("TAIL")}.
 * {@code render} is declared on {@code Drawable} (intermediary {@code method_25394},
 * descriptor {@code (Lnet/minecraft/client/util/math/MatrixStack;IIF)V}; verified
 * against yarn 1.17.1+build.65); {@code Screen} carries the concrete override, so
 * the injection lands at the end of whatever screen is on top. The
 * {@code MatrixStack} is not forwarded — {@code ScreenFade} paints in raw GL, so
 * the geometry is unchanged.
 *
 * <p><b>1.17.1 core-profile status: TRIGGER wired, DRAW is a no-op.</b>
 * {@link ScreenFade} is STUBBED on 1.17.1 (its immediate-mode blit is illegal under
 * the OpenGL 3.2 core profile), so {@link ScreenFade#draw()} /
 * {@link ScreenFade#captureFrame()} do nothing and a screen change is a hard cut.
 * Visual-TODO: core-profile VAO/FADE-program rewrite of ScreenFade
 * (CORE_PROFILE_SPEC §7). The injection stays because it is harmless and becomes
 * live the instant ScreenFade is un-stubbed.
 */
@Mixin(Screen.class)
public abstract class ScreenFadeMixin {

    // Screen open: dissolve above the screen, then snapshot the finished frame.
    // Same order as Forge onScreenPost: draw() then captureFrame().
    @Inject(method = "render", at = @At("TAIL"))
    private void s1mp1e$fadeDrawScreen(MatrixStack matrices, int mouseX, int mouseY,
                                       float delta, CallbackInfo ci) {
        ScreenFade.draw();
        ScreenFade.captureFrame();
    }
}

/**
 * System 1 — STARTER half (documented counterpart; not registered on its own —
 * the live STARTER is {@link MinecraftClientFadeMixin}).
 *
 * <p>Forge counterpart {@code GlassScreenFadeHandler#onGuiOpen(GuiOpenEvent,
 * priority = LOWEST)}: it compared {@code mc.currentScreen} (outgoing) against
 * {@code e.getGui()} (incoming) and only fired {@link ScreenFade#trigger()} on a
 * REAL change — MC re-sets the SAME screen instance on a window resize and that
 * must not flash.
 *
 * <p>Fabric target {@code MinecraftClient.setScreen(Screen)} — intermediary
 * {@code method_1507}, descriptor {@code (Lnet/minecraft/client/gui/screen/Screen;)V}.
 * <b>Signature delta vs 1.16.5:</b> yarn renamed {@code openScreen -> setScreen}
 * at 1.17. At {@code @At("HEAD")} the {@code currentScreen} field
 * ({@code field_1755}) still holds the OUTGOING screen, so the byte-for-byte
 * equivalent of the Forge guard is {@code this.currentScreen == screen}. No capture
 * happens here on purpose.
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
