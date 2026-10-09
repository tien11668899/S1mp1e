package dev.s1mp1e.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.s1mp1e.client.gui.SettingsShell;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A widget that a settings-page row stands in for paints nothing of its own: {@code extractRenderState} still runs
 * (hover, tooltip, narration state), only the call into the widget's picture is skipped. One hook for every widget
 * kind — buttons, cycle buttons, sliders.
 */
@Mixin(AbstractWidget.class)
public abstract class WidgetShellSuppressMixin {

    @WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/components/AbstractWidget;extractWidgetRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V"))
    private void s1mp1e$rowStandsIn(AbstractWidget self, GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta,
                                    Operation<Void> original) {
        if (!SettingsShell.suppresses(self)) original.call(self, graphics, mouseX, mouseY, delta);
    }
}
