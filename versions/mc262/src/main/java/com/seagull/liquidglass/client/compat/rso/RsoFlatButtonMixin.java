package com.seagull.liquidglass.client.compat.rso;

import com.seagull.liquidglass.client.compat.RsoGlass;
import me.flashyreese.mods.reeses_sodium_options.client.gui.widget.FlatButtonWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Flat buttons expose "enabled" (so the glass capsule can dim a disabled one, e.g. Apply with nothing to apply) and
 * their label width (so a capsule is made wide enough that the label clears its round ends).
 */
@Mixin(FlatButtonWidget.class)
public abstract class RsoFlatButtonMixin implements RsoGlass.FlatState {
   @Shadow private boolean enabled;
   @Shadow @Final private Component label;

   @Override
   public boolean lg$enabled() {
      return this.enabled;
   }

   @Override
   public int lg$labelWidth() {
      return this.label == null ? 0 : Minecraft.getInstance().font.width(this.label);
   }
}
