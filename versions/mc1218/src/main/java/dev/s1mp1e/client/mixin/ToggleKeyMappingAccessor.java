package dev.s1mp1e.client.mixin;

import java.util.function.BooleanSupplier;
import net.minecraft.client.ToggleKeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The "is this key in toggle mode right now" supplier vanilla asks on every key edge (see {@code ToggleKeyOptionsMixin}). */
@Mixin(ToggleKeyMapping.class)
public interface ToggleKeyMappingAccessor {

    @Accessor("needsToggle")
    BooleanSupplier s1mp1e$getNeedsToggle();

    @Mutable
    @Accessor("needsToggle")
    void s1mp1e$setNeedsToggle(BooleanSupplier supplier);
}
