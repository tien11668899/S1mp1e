package dev.s1mp1e.glass.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.s1mp1e.client.gui.SettingsShell;
import dev.s1mp1e.glass.ui.GlassTooltip;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The one call every screen's frame goes through — {@code currentScreen.render(matrices, mouseX, mouseY, delta)} in
 * {@code GameRenderer.render(float, long, boolean)} (javap-verified on 1.19.2: the only {@code Screen.render} INVOKE
 * there; 1.19.2 has no {@code renderWithTooltip}) — is wrapped for two things:
 *
 * <p><b>Feature E / user rule R1 — the glass tooltip card is the VERY TOP GUI layer.</b> In 1.19.2 the toasts are not
 * drawn by {@code GameRenderer} at all but by {@code MinecraftClient.render} right after it returns, so a tooltip drawn
 * inside the screen render would sit UNDER a toast. The screen render is therefore opened with
 * {@link GlassTooltip#beginDefer()} (glass tooltips are recorded, not drawn) and {@code MinecraftClientTopLayerMixin}
 * draws the recorded card — plus its 150 ms ghost and a running screen cross-dissolve — after the toasts.
 *
 * <p><b>The settings shell.</b> Every 1.19.2 settings page overrides {@code render} (background, list, title,
 * {@code super.render}, its own tooltip), so a hook on {@code Screen.render} itself would only run after the page had
 * painted itself; here the whole page render is replaced by {@link SettingsShell#render} for the pages it handles.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererTooltipLayerMixin {

    @WrapOperation(method = "render",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screen/Screen;render(Lnet/minecraft/client/util/math/MatrixStack;IIF)V"))
    private void s1mp1e$screenFrame(Screen screen, MatrixStack matrices, int mouseX, int mouseY, float delta,
                                    Operation<Void> op) {
        GlassTooltip.beginDefer();
        if (SettingsShell.handles(screen)) {
            SettingsShell.render(screen, matrices, mouseX, mouseY, delta);
        } else {
            op.call(screen, matrices, mouseX, mouseY, delta);
        }
    }
}
