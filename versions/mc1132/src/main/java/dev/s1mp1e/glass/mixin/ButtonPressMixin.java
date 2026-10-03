package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.anim.PressPulse;
import net.minecraft.client.gui.widget.ButtonWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Records the press time of every {@link ButtonWidget} (glass buttons, cycle buttons, toggles …) so
 * {@code ButtonGlassMixin} can dip it on a tap — the 1.21.1 counterpart of the trigger half of 26.2's
 * {@code ButtonPressPulseMixin}.
 *
 * <p>Vanilla activates on mouse-down, so this is a quick tap pulse: {@code onClick} (concrete on
 * {@code ButtonWidget}, calls {@code onPress}) fires on a click, and {@code keyPressed} fires on Enter/Space while
 * focused. {@code onPress} itself is abstract (no body to inject), so the two concrete callers are hooked instead. The
 * actual pose scale is applied at the draw site — see {@link PressPulse} and {@code ButtonGlassMixin}.
 */
@Mixin(ButtonWidget.class)
public abstract class ButtonPressMixin {
    /**
     * 1.13.2: every button with behaviour is an anonymous subclass overriding {@code method_18374} (the press)
     * without calling super, so the press is stamped where {@code ButtonWidget.mouseClicked} has decided the click
     * hit: right before it plays the click sound. (Buttons have no keyboard activation before 1.14.)
     */
    @Inject(method = "mouseClicked",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/widget/ButtonWidget;playDownSound(Lnet/minecraft/client/sound/SoundManager;)V"))
    private void s1mp1e$pulseOnClick(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        PressPulse.press((ButtonWidget) (Object) this);
    }
}
