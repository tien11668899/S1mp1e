package com.seagull.liquidglass.client.compat.rso;

import com.seagull.liquidglass.client.compat.RsoGlass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/** Integer slider rows expose "held" and carry the knob's lens animation state. */
@Mixin(targets = "me.flashyreese.mods.reeses_sodium_options.client.gui.frame.option.IntegerSliderOptionRow")
public abstract class RsoSliderRowMixin implements RsoGlass.SliderRow {
   @Shadow private boolean sliderHeld;
   @Unique private RsoGlass.Knob lg$knob;

   @Override
   public boolean lg$held() {
      return this.sliderHeld;
   }

   @Override
   public RsoGlass.Knob lg$knob() {
      if (this.lg$knob == null) this.lg$knob = new RsoGlass.Knob();
      return this.lg$knob;
   }
}
