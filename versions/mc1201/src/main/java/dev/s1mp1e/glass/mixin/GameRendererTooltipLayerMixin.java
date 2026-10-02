package dev.s1mp1e.glass.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.s1mp1e.glass.render.ScreenDissolve;
import dev.s1mp1e.glass.ui.GlassTooltip;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.toast.ToastManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feature E / user rule R1 — the glass tooltip card (and its 150 ms fade-out ghost) is the VERY TOP GUI layer.
 *
 * <p>Seam (verified in the decompiled 1.20.1 {@code GameRenderer.render(float, long, boolean)}, the same order as
 * 1.21.1): after the world
 * and the HUD it calls {@code currentScreen.renderWithTooltip(drawContext, mouseX, mouseY, delta)}, then
 * {@code InGameHud.renderAutosaveIndicator}, then {@code ToastManager.draw(drawContext)}, then the final
 * {@code drawContext.draw()}. A tooltip drawn inside the screen render would therefore sit UNDER a toast (and the
 * autosave indicator). This is the immediate-mode equivalent of 26.2's {@code TooltipLayer.promote()}: the screen render
 * is wrapped in {@link GlassTooltip#beginDefer()} (glass tooltips are recorded, not drawn), and right after the toasts
 * {@link GlassTooltip#endDefer} draws the recorded card — flush + forced {@code grabNow()} of everything drawn below it,
 * card, grey scrim, text at z 400 — plus the ghost. A render that never reaches the toast call (defensive) is closed at
 * {@code render} TAIL without drawing.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererTooltipLayerMixin {

    @WrapOperation(method = "render",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screen/Screen;renderWithTooltip(Lnet/minecraft/client/gui/DrawContext;IIF)V"))
    private void s1mp1e$deferTooltips(Screen screen, DrawContext ctx, int mouseX, int mouseY, float delta,
                                      Operation<Void> op) {
        GlassTooltip.beginDefer();
        op.call(screen, ctx, mouseX, mouseY, delta);
    }

    @WrapOperation(method = "render",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/toast/ToastManager;draw(Lnet/minecraft/client/gui/DrawContext;)V"))
    private void s1mp1e$tooltipTopLayer(ToastManager toasts, DrawContext ctx, Operation<Void> op) {
        op.call(toasts, ctx);
        GlassTooltip.endDefer(ctx);   // the recorded tooltip + ghost: the top GUI layer of the frame
        ScreenDissolve.draw(ctx);     // ...under only the outgoing-screen snapshot of a running cross-dissolve
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void s1mp1e$closeDefer(float tickDelta, long startTime, boolean tick, CallbackInfo ci) {
        s1mp1e$safety();
    }

    @Unique
    private static void s1mp1e$safety() {
        if (GlassTooltip.deferring()) GlassTooltip.endDefer(null);   // never leak the window into the next frame
    }
}
