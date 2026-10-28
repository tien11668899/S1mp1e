package dev.s1mp1e.glass.mixin;

import java.util.function.BooleanSupplier;
import dev.s1mp1e.client.module.ToggleSprintModule;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ToggleSprint (1.21.1): {@code GameOptions} builds {@code sprintKey} / {@code sneakKey} as {@code StickyKeyBinding}s
 * whose toggle mode is {@code sprintToggled::getValue} / {@code sneakToggled::getValue}. Once they exist, widen each
 * supplier to "vanilla option OR {@link ToggleSprintModule}", so the module rides vanilla's own toggle behaviour
 * instead of simulating key presses, and the player's options.txt values are left untouched.
 */
@Mixin(GameOptions.class)
public abstract class ToggleKeyOptionsMixin {

    @Inject(method = "<init>", at = @At("TAIL"))
    private void s1mp1e$widenToggleKeys(CallbackInfo ci) {
        GameOptions o = (GameOptions) (Object) this;
        s1mp1e$widen(o.sprintKey, ToggleSprintModule::sprintToggle);
        s1mp1e$widen(o.sneakKey, ToggleSprintModule::sneakToggle);
    }

    private static void s1mp1e$widen(KeyBinding key, BooleanSupplier ours) {
        if (!(key instanceof StickyKeyBindingAccessor acc)) return;   // another mod replaced the binding: leave it alone
        BooleanSupplier vanilla = acc.s1mp1e$getToggleGetter();
        acc.s1mp1e$setToggleGetter(() -> ours.getAsBoolean() || vanilla.getAsBoolean());
    }
}
