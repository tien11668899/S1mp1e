package dev.s1mp1e.o.mixin;

import dev.s1mp1e.o.glass.hook.LockButtonHook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.widget.LockButtonWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * allglass #21 — the world-difficulty lock button (create-world screen) becomes a frosted round glass button with a
 * hand-drawn padlock glyph. {@code LockButtonWidget.render(Minecraft,int,int)} overrides {@code ButtonWidget.render},
 * so the generic {@code ButtonWidgetMixin} capsule splice never reaches it: head-cancel here and let
 * {@link LockButtonHook} own the whole button.
 */
@Mixin(value = LockButtonWidget.class, priority = 1100)
public abstract class LockButtonWidgetMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$glass(Minecraft mc, int mouseX, int mouseY, CallbackInfo ci) {
        if (LockButtonHook.draw((LockButtonWidget) (Object) this, mc, mouseX, mouseY)) ci.cancel();
    }
}
