package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.anim.PressPulse;
import net.minecraft.client.gui.widget.AbstractButtonWidget;
import net.minecraft.client.gui.widget.AbstractPressableButtonWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Records the press time of every {@link AbstractPressableButtonWidget} (glass buttons, cycle buttons, toggles …) so
 * {@code ButtonGlassMixin} can dip it on a tap — the 1.21.1 counterpart of the trigger half of 26.2's
 * {@code ButtonPressPulseMixin}.
 *
 * <p>Vanilla activates on mouse-down, so this is a quick tap pulse: {@code onClick} (concrete on
 * {@code AbstractPressableButtonWidget}, calls {@code onPress}) fires on a click, and {@code keyPressed} fires on Enter/Space while
 * focused. {@code onPress} itself is abstract (no body to inject), so the two concrete callers are hooked instead. The
 * actual pose scale is applied at the draw site — see {@link PressPulse} and {@code ButtonGlassMixin}.
 */
@Mixin(AbstractPressableButtonWidget.class)
public abstract class ButtonPressMixin {

    @Inject(method = "onClick", at = @At("HEAD"))
    private void s1mp1e$pulseOnClick(double mouseX, double mouseY, CallbackInfo ci) {
        PressPulse.press((AbstractButtonWidget) (Object) this);
    }

    @Inject(method = "keyPressed", at = @At("RETURN"))
    private void s1mp1e$pulseOnKey(int keyCode, int scanCode, int modifiers, CallbackInfoReturnable<Boolean> cir) {
        if (Boolean.TRUE.equals(cir.getReturnValue())) PressPulse.press((AbstractButtonWidget) (Object) this);
    }
}
