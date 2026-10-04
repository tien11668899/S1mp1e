package dev.s1mp1e.o.mixin;

import dev.s1mp1e.o.glass.anim.PressPulse;
import dev.s1mp1e.o.glass.asm.ButtonHook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.sound.system.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** coremod「GuiButton.drawButton」：玻璃膠囊畫好就不畫原版 widgets.png；playPressSound 開頭觸發按下脈衝。 */
@Mixin(value = ButtonWidget.class, priority = 1100)
public abstract class ButtonWidgetMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$glassButton(Minecraft mc, int mx, int my, CallbackInfo ci) {
        if (ButtonHook.draw((ButtonWidget) (Object) this, mc, mx, my)) ci.cancel();
    }

    @Inject(method = "playClickSound", at = @At("HEAD"))
    private void s1mp1e$pressPulse(SoundManager sounds, CallbackInfo ci) {
        PressPulse.press((ButtonWidget) (Object) this);
    }
}
