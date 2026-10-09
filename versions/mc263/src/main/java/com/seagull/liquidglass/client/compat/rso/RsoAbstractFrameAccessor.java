package com.seagull.liquidglass.client.compat.rso;

import java.util.List;
import me.flashyreese.mods.reeses_sodium_options.client.gui.frame.AbstractFrame;
import me.flashyreese.mods.reeses_sodium_options.client.gui.frame.option.OptionRow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AbstractFrame.class)
public interface RsoAbstractFrameAccessor {
   @Accessor("optionRows")
   List<OptionRow> lg$optionRows();
}
