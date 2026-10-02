package com.seagull.liquidglass.client.compat.rso;

import com.seagull.liquidglass.client.compat.RsoGlass;
import me.flashyreese.mods.reeses_sodium_options.client.gui.layout.LayoutBounds;
import net.caffeinemc.mods.sodium.client.config.structure.BooleanOption;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Boolean rows: the checkbox becomes an iOS switch. It is drawn at the head of {@code renderControl} for BOTH values
 * (RSO paints a fill only when checked, so hooking the fill would lose the "off" switch); RSO's own fill / border /
 * disabled corners are then skipped by the drawRect router. The row also carries the switch's animation state.
 */
@Mixin(targets = "me.flashyreese.mods.reeses_sodium_options.client.gui.frame.option.BooleanOptionRow")
public abstract class RsoBoolRowMixin implements RsoGlass.BoolRow {
   @Shadow @Final private BooleanOption option;
   @Unique private RsoGlass.Switch lg$sw;

   @Shadow
   private LayoutBounds checkboxDim() {
      throw new AssertionError();
   }

   @Inject(method = "renderControl", at = @At("HEAD"))
   private void lg$switchControl(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
      if (!RsoGlass.active || this.option.shouldHideControl()) return;
      LayoutBounds d = this.checkboxDim();
      RsoGlass.drawSwitch(g, this, d.x(), d.y(), d.getLimitX(), d.getLimitY());
   }

   @Override
   public boolean lg$value() {
      Object v = this.option.getValidatedValue();
      return v instanceof Boolean b && b;
   }

   @Override
   public boolean lg$enabled() {
      return this.option.isEnabled();
   }

   @Override
   public RsoGlass.Switch lg$switch() {
      if (this.lg$sw == null) this.lg$sw = new RsoGlass.Switch();
      return this.lg$sw;
   }
}
