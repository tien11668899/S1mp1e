package dev.s1mp1e.glass.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.s1mp1e.glass.render.ScreenDissolve;
import dev.s1mp1e.glass.ui.GlassTooltip;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.toast.ToastManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The top GUI layer of a frame. 1.15.2 draws the toasts from {@code MinecraftClient.render(boolean)} —
 * {@code gameRenderer.render(...)} then {@code toastManager.draw()} (javap-verified; from 1.20 on
 * that call sits inside {@code GameRenderer.render}, where the 1.20.1 / 1.21.1 lines hook it) — and nothing of the GUI
 * is drawn after them. So right after the toasts:
 * <ol>
 *   <li>{@link GlassTooltip#endDefer} draws the tooltip recorded during the screen render (see
 *       {@code GameRendererTooltipLayerMixin}) and its fade-out ghost — above the screen AND the toasts (rule R1);</li>
 *   <li>{@link ScreenDissolve#draw} lays the outgoing-screen snapshot of a running cross-dissolve over everything.</li>
 * </ol>
 * The GUI projection / model-view that {@code GameRenderer.render} set up are still in place here (the toasts rely on
 * them too).
 */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientTopLayerMixin {

    @WrapOperation(method = "render(Z)V",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/toast/ToastManager;draw()V"))
    private void s1mp1e$topLayer(ToastManager toasts, Operation<Void> op) {
        op.call(toasts);
        GlassTooltip.endDefer();   // the recorded tooltip + ghost: the top GUI layer of the frame
        ScreenDissolve.draw();     // ...under only the outgoing-screen snapshot of a running cross-dissolve
    }
}
