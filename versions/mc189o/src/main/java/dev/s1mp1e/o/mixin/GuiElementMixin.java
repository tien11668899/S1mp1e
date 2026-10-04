package dev.s1mp1e.o.mixin;

import dev.s1mp1e.o.glass.asm.BlitSuppressor;
import net.minecraft.client.gui.GuiElement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** coremod「Gui.drawTexturedModalRect」：玻璃面板生效時只略過容器面板那幾條貼圖。 */
@Mixin(value = GuiElement.class, priority = 1100)
public abstract class GuiElementMixin {
    @Inject(method = "drawTexture(IIIIII)V", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$suppress(int x, int y, int u, int v, int w, int h, CallbackInfo ci) {
        if (BlitSuppressor.consume(x, y, u, v, w, h)) ci.cancel();
    }
}
