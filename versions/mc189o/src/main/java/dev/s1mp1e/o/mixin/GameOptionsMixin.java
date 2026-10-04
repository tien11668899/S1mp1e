package dev.s1mp1e.o.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.s1mp1e.o.client.asm.CameraHooks;
import net.minecraft.client.options.GameOptions;
import net.minecraft.client.options.KeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** coremod「GameSettings.isKeyDown」（靜態）：回傳值同樣經過 CameraHooks.filterHeld。 */
@Mixin(value = GameOptions.class, priority = 1100)
public abstract class GameOptionsMixin {
    @ModifyReturnValue(method = "isPressed(Lnet/minecraft/client/options/KeyBinding;)Z", at = @At("RETURN"))
    private static boolean s1mp1e$held(boolean pressed, @Local(argsOnly = true) KeyBinding kb) {
        return CameraHooks.filterHeld(pressed, kb);
    }
}
