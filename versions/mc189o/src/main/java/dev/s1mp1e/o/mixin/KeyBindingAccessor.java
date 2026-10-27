package dev.s1mp1e.o.mixin;

import net.minecraft.client.options.KeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The binding's raw held state, before {@code KeyBindingMixin}'s filters (ToggleSprint reads its press edges). */
@Mixin(KeyBinding.class)
public interface KeyBindingAccessor {
    @Accessor("pressed")
    boolean s1mp1e$rawPressed();
}
