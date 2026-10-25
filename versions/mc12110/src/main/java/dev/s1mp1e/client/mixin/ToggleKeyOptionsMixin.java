package dev.s1mp1e.client.mixin;

import java.util.function.BooleanSupplier;
import dev.s1mp1e.client.module.ToggleSprintModule;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ToggleSprint (26.3): {@code Options} builds {@code keySprint} / {@code keyShift} as {@code ToggleKeyMapping}s whose
 * toggle mode is {@code toggleSprint::get} / {@code toggleCrouch::get}. Once they exist, widen each supplier to
 * "vanilla option OR {@link ToggleSprintModule}", so the module rides vanilla's own toggle behaviour instead of
 * simulating key presses, and the player's options.txt values are left untouched.
 */
@Mixin(Options.class)
public abstract class ToggleKeyOptionsMixin {

    @Inject(method = "<init>", at = @At("TAIL"))
    private void s1mp1e$widenToggleKeys(CallbackInfo ci) {
        Options o = (Options) (Object) this;
        s1mp1e$widen(o.keySprint, ToggleSprintModule::sprintToggle);
        s1mp1e$widen(o.keyShift, ToggleSprintModule::sneakToggle);
    }

    private static void s1mp1e$widen(KeyMapping key, BooleanSupplier ours) {
        if (!(key instanceof ToggleKeyMappingAccessor acc)) return;   // another mod replaced the mapping: leave it alone
        BooleanSupplier vanilla = acc.s1mp1e$getNeedsToggle();
        acc.s1mp1e$setNeedsToggle(() -> ours.getAsBoolean() || vanilla.getAsBoolean());
    }
}
