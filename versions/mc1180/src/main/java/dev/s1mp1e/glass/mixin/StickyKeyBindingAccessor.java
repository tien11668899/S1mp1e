package dev.s1mp1e.glass.mixin;

import java.util.function.BooleanSupplier;
import net.minecraft.client.option.StickyKeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The "is this key in toggle mode right now" supplier vanilla asks on every key edge (see {@code ToggleKeyOptionsMixin}). */
@Mixin(StickyKeyBinding.class)
public interface StickyKeyBindingAccessor {

    @Accessor("toggleGetter")
    BooleanSupplier s1mp1e$getToggleGetter();

    @Mutable
    @Accessor("toggleGetter")
    void s1mp1e$setToggleGetter(BooleanSupplier supplier);
}
