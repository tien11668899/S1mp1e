package dev.s1mp1e.glass.compat.sodium;

import dev.s1mp1e.glass.compat.SodiumGlass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Exposes the integer slider element's {@code sliderHeld} flag, so the glass thumb morphs into a refracting lens while
 * it is dragged and the slider stays out although the cursor left the row. (The element class is private.)
 */
@Mixin(targets = "me.jellysquid.mods.sodium.client.gui.options.control.SliderControl$Button", remap = false)
public abstract class SodiumSliderMixin implements SodiumGlass.SliderRow {
    @Shadow private boolean sliderHeld;
    @Shadow @org.spongepowered.asm.mixin.Final private net.minecraft.client.util.math.Rect2i sliderBounds;

    @Override
    public net.minecraft.client.util.math.Rect2i s1mp1e$bounds() {
        return this.sliderBounds;
    }

    @Override
    public boolean s1mp1e$held() {
        return this.sliderHeld;
    }
}
