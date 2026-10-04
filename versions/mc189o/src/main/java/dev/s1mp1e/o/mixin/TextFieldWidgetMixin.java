package dev.s1mp1e.o.mixin;

import dev.s1mp1e.o.glass.hook.EditBoxHook;
import net.minecraft.client.gui.widget.TextFieldWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** coremod 第 6 組：文字框打字動畫——EditBoxHook 畫好就不畫原版。 */
@Mixin(value = TextFieldWidget.class, priority = 1100)
public abstract class TextFieldWidgetMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$typing(CallbackInfo ci) {
        if (EditBoxHook.draw((TextFieldWidget) (Object) this)) ci.cancel();
    }
}
