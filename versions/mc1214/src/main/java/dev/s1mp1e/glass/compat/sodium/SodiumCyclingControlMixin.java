package dev.s1mp1e.glass.compat.sodium;

import dev.s1mp1e.glass.compat.SodiumGlass;
import net.caffeinemc.mods.sodium.client.gui.options.control.CyclingControl;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Exposes {@code CyclingControl}'s private value names (indexed by the enum ordinal). */
@Mixin(value = CyclingControl.class, remap = false)
public abstract class SodiumCyclingControlMixin implements SodiumGlass.CycleInfo {
    @Shadow @Final private Text[] names;

    @Override
    public Text[] s1mp1e$names() {
        return this.names;
    }
}
