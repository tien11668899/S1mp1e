package dev.s1mp1e.glass.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.s1mp1e.client.gui.SettingsShell;
import dev.s1mp1e.glass.ui.GlassTooltip;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.class_4218;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The one call every screen's frame goes through — {@code currentScreen.render(mouseX, mouseY, delta)} in
 * {@code class_4218.render(float, long, boolean)} (javap-verified on 1.13.2: the only {@code Screen.render} INVOKE
 * there; 1.13.2 has no {@code renderWithTooltip}) — is wrapped for two things:
 *
 * <p><b>Feature E / user rule R1 — the glass tooltip card is the VERY TOP GUI layer.</b> In 1.13.2 the toasts are not
 * drawn by {@code class_4218} at all but by {@code MinecraftClient.render} right after it returns, so a tooltip drawn
 * inside the screen render would sit UNDER a toast. The screen render is therefore opened with
 * {@link GlassTooltip#beginDefer()} (glass tooltips are recorded, not drawn) and {@code MinecraftClientTopLayerMixin}
 * draws the recorded card — plus its 150 ms ghost and a running screen cross-dissolve — after the toasts.
 *
 * <p><b>The settings shell.</b> Every 1.13.2 settings page overrides {@code render} (background, list, title,
 * {@code super.render}, its own tooltip), so a hook on {@code Screen.render} itself would only run after the page had
 * painted itself; here the whole page render is replaced by {@link SettingsShell#render} for the pages it handles.
 */
@Mixin(class_4218.class)
public abstract class GameRendererTooltipLayerMixin {

    @WrapOperation(method = "method_19061",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screen/Screen;render(IIF)V"))
    private void s1mp1e$screenFrame(Screen screen, int mouseX, int mouseY, float delta,
                                    Operation<Void> op) {
        GlassTooltip.beginDefer();
        if (SettingsShell.handles(screen)) {
            SettingsShell.render(screen, mouseX, mouseY, delta);
        } else {
            op.call(screen, mouseX, mouseY, delta);
        }
    }
}
