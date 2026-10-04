package dev.s1mp1e.o.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.s1mp1e.o.client.asm.CameraHooks;
import net.minecraft.client.options.KeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** coremod「Zoom 擋掉其他模組的縮放鍵」：按住（isPressed）與點一下（consumeClick）的回傳值都經過 CameraHooks 過濾。 */
@Mixin(value = KeyBinding.class, priority = 1100)
public abstract class KeyBindingMixin {
    @ModifyReturnValue(method = "isPressed", at = @At("RETURN"))
    private boolean s1mp1e$held(boolean pressed) {
        return CameraHooks.filterHeld(pressed, (KeyBinding) (Object) this);
    }

    @ModifyReturnValue(method = "consumeClick", at = @At("RETURN"))
    private boolean s1mp1e$click(boolean pressed) {
        return CameraHooks.filterClick(pressed, (KeyBinding) (Object) this);
    }
}
