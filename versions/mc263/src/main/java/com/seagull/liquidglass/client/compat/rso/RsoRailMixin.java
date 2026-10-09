package com.seagull.liquidglass.client.compat.rso;

import com.seagull.liquidglass.client.compat.RsoGlass;
import me.flashyreese.mods.reeses_sodium_options.client.gui.layout.LayoutBounds;
import me.flashyreese.mods.reeses_sodium_options.client.gui.widget.BaseWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** The tab rail's bounds and selected page widget, for the glass rail panel + sliding pill. */
@Mixin(targets = "me.flashyreese.mods.reeses_sodium_options.client.gui.frame.tab.TabRail")
public abstract class RsoRailMixin implements RsoGlass.Rail {
   @Shadow private LayoutBounds dim;
   @Shadow private BaseWidget selectedTabWidget;

   @Override
   public LayoutBounds lg$dim() {
      return this.dim;
   }

   @Override
   public BaseWidget lg$selectedWidget() {
      return this.selectedTabWidget;
   }
}
