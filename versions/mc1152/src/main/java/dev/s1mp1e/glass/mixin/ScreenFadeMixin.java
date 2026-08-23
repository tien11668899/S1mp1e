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
 * System 1 — screen open/close cross-dissolve. The Fabric 1.15.2 port of Forge
 * {@code GlassScreenFadeHandler}. Nothing about the animation changes; only the
 * plumbing does — {@code @SubscribeEvent} handlers become {@code @Inject}
 * mixins that call the SAME {@link ScreenFade} core (already ported to 1.15.2),
 * in the SAME order, so the dissolve is byte-for-byte identical.
 *
 * <p><b>DRAW half, screen branch.</b> Forge counterpart
 * {@code GlassScreenFadeHandler#onScreenPost(GuiScreenEvent.DrawScreenEvent.Post)}:
 * over a newly opened screen, draw the outgoing frame then snapshot the finished
 * one — {@link ScreenFade#draw()} BEFORE {@link ScreenFade#captureFrame()}.
 *
 * <p>Fabric target {@code Screen.render(int, int, float)} at {@code @At("TAIL")}.
 * {@code render} is the concrete override on {@code Screen}, so the injection lands
 * at the end of whatever screen is on top.
 *
 * <p><b>1.15.2 delta vs 1.16.5.</b> The 1.16.5 source targeted
 * {@code Screen.render(MatrixStack, int, int, float)} ({@code method_25394},
 * {@code (Lnet/minecraft/client/util/math/MatrixStack;IIF)V}). In 1.15.2 the GUI
 * render methods predate the {@code MatrixStack} thread: {@code Screen.render} is
 * {@code (IIF)V}, so the injected signature DROPS the leading {@code MatrixStack}.
 * It was never forwarded anyway — {@code ScreenFade} paints in raw GL — so the
 * geometry is unchanged.
 */
@Mixin(Screen.class)
public abstract class ScreenFadeMixin {

    // Screen open: dissolve above the screen, then snapshot the finished frame.
    // Same order as Forge onScreenPost: draw() then captureFrame().
    @Inject(method = "render", at = @At("TAIL"))
    private void s1mp1e$fadeDrawScreen(int mouseX, int mouseY,
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
 * <p>Fabric target {@code MinecraftClient.openScreen(Screen)} — intermediary
 * {@code method_1507}, descriptor
 * {@code (Lnet/minecraft/client/gui/screen/Screen;)V}. At {@code @At("HEAD")} the
 * {@code currentScreen} field ({@code field_1755}) still holds the OUTGOING
 * screen, so the byte-for-byte equivalent of the Forge guard is
 * {@code this.currentScreen == screen}. No capture happens here on purpose.
 *
 * <p>Not registered in {@code s1mp1e.mixins.json} (the active starter is
 * {@link MinecraftClientFadeMixin}); carried here to mirror the 1.16.5 source
 * layout verbatim. No {@code MatrixStack} is involved, so it is copied unchanged.
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
