package dev.s1mp1e.glass.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.s1mp1e.client.gui.AllGlass;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.NarratedMultilineTextWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Message boxes (out-of-memory, generic message, accessibility onboarding text, …) were a black box with a grey/white
 * border. The black fill becomes a glass plate with a grey readability scrim; the border fills only draw while focused
 * (keyboard focus stays visible).
 */
@Mixin(NarratedMultilineTextWidget.class)
public abstract class NarratedTextGlassMixin {

    @WrapOperation(method = "renderWidget", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/DrawContext;fill(IIIII)V"))
    private void s1mp1e$glass(DrawContext ctx, int x0, int y0, int x1, int y1, int argb, Operation<Void> original) {
        if (argb == 0xFF000000) {
            AllGlass.plate(ctx, x0, y0, x1, y1, 1f, 0x38000000);
        } else if (((NarratedMultilineTextWidget) (Object) this).isFocused()) {
            original.call(ctx, x0, y0, x1, y1, argb);
        }
    }
}
