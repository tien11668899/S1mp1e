package dev.s1mp1e.glass.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.s1mp1e.client.gui.AllGlass;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.NarratedMultilineTextWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Message boxes (out-of-memory, generic message, accessibility onboarding text, …) were a translucent box
 * ({@code 0x55000000}) with a grey border ({@code 0xFFA0A0A0}). The box fill becomes a glass plate with a grey
 * readability scrim; the border fills only draw while focused (keyboard focus stays visible). 1.20.1 draws the box in
 * {@code renderButton} (the 1.21.1 line used {@code renderWidget}).
 */
@Mixin(NarratedMultilineTextWidget.class)
public abstract class NarratedTextGlassMixin {

    @WrapOperation(method = "renderButton", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/DrawContext;fill(IIIII)V"))
    private void s1mp1e$glass(DrawContext ctx, int x0, int y0, int x1, int y1, int argb, Operation<Void> original) {
        if ((argb & 0xFF000000) != 0 && (argb & 0xFFFFFF) == 0) {   // the translucent black box (0x55000000)
            AllGlass.plate(ctx, x0, y0, x1, y1, 1f, 0x38000000);
        } else if (((NarratedMultilineTextWidget) (Object) this).isFocused()) {
            original.call(ctx, x0, y0, x1, y1, argb);
        }
    }
}
