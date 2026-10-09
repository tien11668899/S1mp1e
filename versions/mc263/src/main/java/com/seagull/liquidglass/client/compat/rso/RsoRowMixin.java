package com.seagull.liquidglass.client.compat.rso;

import com.seagull.liquidglass.client.compat.RsoGlass;
import dev.s1mp1e.client.gui.GuiAlpha;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks option rows (package-private {@code AbstractOptionRow}) for {@link RsoGlass#rect}, and cascades each row in
 * (rise + fade, staggered) when a page/tab is switched — {@link RsoGlass#rowEnter}.
 */
@Mixin(targets = "me.flashyreese.mods.reeses_sodium_options.client.gui.frame.option.AbstractOptionRow")
public abstract class RsoRowMixin implements RsoGlass.Row {
   @Unique private boolean lg$entered;

   @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
   private void lg$enter(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
      lg$entered = false;
      if (!RsoGlass.active) return;
      float[] a = RsoGlass.rowEnter(this);
      if (a == null) return;
      if (a[1] <= 0.003F) { ci.cancel(); return; }                 // not this row's turn yet: draw nothing
      g.pose().pushMatrix();
      g.pose().translate(0F, a[0]);
      GuiAlpha.push(a[1]);
      lg$entered = true;
   }

   @Inject(method = "extractRenderState", at = @At("RETURN"))
   private void lg$enterEnd(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
      if (!lg$entered) return;
      lg$entered = false;
      GuiAlpha.pop();
      g.pose().popMatrix();
   }
}
