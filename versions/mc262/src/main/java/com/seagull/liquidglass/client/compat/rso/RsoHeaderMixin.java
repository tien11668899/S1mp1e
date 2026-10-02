package com.seagull.liquidglass.client.compat.rso;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.seagull.liquidglass.client.compat.RsoGlass;
import me.flashyreese.mods.reeses_sodium_options.client.gui.widget.TabHeaderWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Rail mod headers: the version line uses the theme's DARK accent (e.g. Sodium's deep teal), unreadable on glass —
 * use the system secondary label grey instead.
 */
@Mixin(TabHeaderWidget.class)
public abstract class RsoHeaderMixin {
   @ModifyReturnValue(method = "secondaryTextColor", at = @At("RETURN"))
   private int lg$secondary(int original) {
      return RsoGlass.active ? 0xFFAEAEB2 : original;
   }
}
