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
 * System 1 — screen open/close cross-dissolve. The Fabric 1.16.5 port of Forge
 * {@code GlassScreenFadeHandler}. Nothing about the animation changes; only the
 * plumbing does — {@code @SubscribeEvent} handlers become {@code @Inject}
 * mixins that call the SAME {@link ScreenFade} core (already ported to 1.16.5),
 * in the SAME order, so the dissolve is byte-for-byte identical:
 *
 * <ul>
 *   <li>the screen change only STARTS the dissolve ({@link ScreenFade#trigger()}),
 *       it never captures — capture from outside the render pass grabbed an
 *       unfilled texture and flashed white (see the class note on {@code ScreenFade});</li>
 *   <li>every rendered frame draws the outgoing snapshot then captures the
 *       finished frame — {@link ScreenFade#draw()} BEFORE
 *       {@link ScreenFade#captureFrame()};</li>
 *   <li>capture pauses for the duration of a dissolve (handled inside
 *       {@code ScreenFade}) so the fade is never snapshotted into itself.</li>
 * </ul>
 *
 * <p>The three Forge handlers split across three MC targets, so they cannot live
 * under a single {@code @Mixin} (the config's {@code defaultRequire:1} would fail
 * the injector on the target that lacks the method). They are grouped in this one
 * file as separate single-target mixin classes:
 * <ul>
 *   <li>{@code onGuiOpen} (STARTER) &rarr; {@link ScreenFadeOpenMixin} on
 *       {@code MinecraftClient.openScreen};</li>
 *   <li>{@code onScreenPost} (DRAW, screen branch) &rarr; this class on
 *       {@code Screen.render};</li>
 *   <li>{@code onOverlayPost} (DRAW, in-world branch) &rarr; the render-TAIL added
 *       to {@code InGameHudMixin} (guarded by {@code currentScreen == null}).</li>
 * </ul>
 *
 * <p><b>DRAW half, screen branch.</b> Forge counterpart
 * {@code GlassScreenFadeHandler#onScreenPost(GuiScreenEvent.DrawScreenEvent.Post)}:
 * over a newly opened screen, draw the outgoing frame then snapshot the finished
 * one. Fabric target {@code Screen.render(MatrixStack, int, int, float)} at
 * {@code @At("TAIL")}. {@code render} is declared on {@code Drawable}
 * (intermediary {@code method_25394}, descriptor
 * {@code (Lnet/minecraft/client/util/math/MatrixStack;IIF)V}); {@code Screen}
 * carries the concrete override, so the injection lands at the end of whatever
 * screen is on top. The {@code MatrixStack} is the mandatory first arg in 1.16.5
 * but is not forwarded — {@code ScreenFade} paints in raw GL exactly like the
 * Forge line, so the geometry is unchanged.
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
 * System 1 — STARTER half. Forge counterpart
 * {@code GlassScreenFadeHandler#onGuiOpen(GuiOpenEvent, priority = LOWEST)}: it
 * compared {@code mc.currentScreen} (outgoing) against {@code e.getGui()}
 * (incoming) and only fired {@link ScreenFade#trigger()} on a REAL change — MC
 * re-sets the SAME screen instance on a window resize and that must not flash.
 *
 * <p>Fabric target {@code MinecraftClient.openScreen(Screen)} — the yarn-1.16.5
 * name of what later mappings call {@code setScreen}; intermediary
 * {@code method_1507}, descriptor
 * {@code (Lnet/minecraft/client/gui/screen/Screen;)V}. At {@code @At("HEAD")} the
 * {@code currentScreen} field ({@code field_1755}) still holds the OUTGOING
 * screen, so the byte-for-byte equivalent of the Forge guard is
 * {@code this.currentScreen == screen}. No capture happens here on purpose.
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
