package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.MerchantGlide;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feature D (merchant): no trade hover tooltip while the trade list glides. {@code MerchantScreen.render} calls
 * {@code WidgetButtonPage.renderTooltip} for the hovered trade button AFTER the trade loop; mid-glide the buttons' hit
 * boxes are the row-aligned logical layout while the rows are drawn between positions, so the tooltip would describe a
 * different trade than the one drawn under the pointer. {@link MerchantGlide} is armed only while the list glides (render
 * HEAD..TAIL), so this is a strict pass-through at rest. Targeted by name because {@code WidgetButtonPage} is
 * package-private ({@code renderTooltip(MatrixStack,II)V} is declared on it — javap-verified).
 */
@Mixin(targets = "net.minecraft.client.gui.screen.ingame.MerchantScreen$WidgetButtonPage")
public abstract class MerchantTradeButtonMixin {

    @Inject(method = "renderTooltip(Lnet/minecraft/client/util/math/MatrixStack;II)V", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$noTooltipMidGlide(MatrixStack matrices, int mouseX, int mouseY, CallbackInfo ci) {
        if (MerchantGlide.isActive()) ci.cancel();
    }
}
