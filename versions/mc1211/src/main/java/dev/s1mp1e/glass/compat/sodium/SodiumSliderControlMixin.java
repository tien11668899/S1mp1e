package dev.s1mp1e.glass.compat.sodium;

import dev.s1mp1e.glass.compat.SodiumGlass;
import net.caffeinemc.mods.sodium.client.gui.options.control.ControlValueFormatter;
import net.caffeinemc.mods.sodium.client.gui.options.control.SliderControl;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Exposes {@code SliderControl}'s private range and value formatter (the value text and the thumb position). */
@Mixin(value = SliderControl.class, remap = false)
public abstract class SodiumSliderControlMixin implements SodiumGlass.SliderInfo {
    @Shadow @Final private int min;
    @Shadow @Final private int max;
    @Shadow @Final private int interval;

    @Override
    public int s1mp1e$interval() {
        return this.interval;
    }
    @Shadow @Final private ControlValueFormatter mode;

    @Override
    public int s1mp1e$min() {
        return this.min;
    }

    @Override
    public int s1mp1e$max() {
        return this.max;
    }

    @Override
    public Text s1mp1e$format(int value) {
        return this.mode.format(value);
    }
}
