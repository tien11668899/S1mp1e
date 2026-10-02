package com.seagull.liquidglass.client.mixin;

import dev.s1mp1e.client.gui.SmoothScrollHost;
import net.minecraft.client.gui.components.AbstractScrollArea;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Smooth (eased) scrolling for every scroll area — the world / server / language / resource-pack / key-binding lists,
 * option lists and scrolling text areas. Vanilla's {@code mouseScrolled} jumps straight to
 * {@code scrollAmount - dy * scrollRate}; that single {@code setScrollAmount} is redirected into a target, notches keep
 * adding to the target (so a fast flick travels further), and the real scroll amount eases toward it one step per frame
 * (τ ≈ 85 ms, no overshoot). Rendering, hit-testing and entry positions all read the one real scroll amount, so a click
 * mid-glide always lands on what is drawn under the cursor.
 *
 * <p>Any other {@code setScrollAmount} (scrollbar drag, programmatic centring, clamping after a resize) cancels the glide,
 * so every non-wheel path keeps vanilla's exact behaviour.
 */
@Mixin(AbstractScrollArea.class)
public abstract class ScrollAreaSmoothMixin implements SmoothScrollHost {

   @Unique private static final double LG_TAU = 0.085;

   @Shadow public abstract double scrollAmount();
   @Shadow public abstract void setScrollAmount(double amount);
   @Shadow public abstract int maxScrollAmount();

   @Unique private double lg$target = Double.NaN;
   @Unique private long lg$lastNs;
   @Unique private boolean lg$stepping;

   @Redirect(
      method = "mouseScrolled",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/AbstractScrollArea;setScrollAmount(D)V")
   )
   private void lg$wheel(AbstractScrollArea self, double requested) {
      liquidglass$glideTo(requested);
   }

   /** A direct set that is not our own step cancels the glide (drag, centring, clamping). */
   @Inject(method = "setScrollAmount", at = @At("HEAD"))
   private void lg$externalSet(double amount, CallbackInfo ci) {
      if (!lg$stepping) { lg$target = Double.NaN; lg$lastNs = 0L; }
   }

   @Override
   public void liquidglass$glideTo(double requested) {
      double cur = scrollAmount();
      double base = Double.isNaN(lg$target) ? cur : lg$target;
      double t = base + (requested - cur);
      int max = maxScrollAmount();
      if (Double.isNaN(lg$target)) lg$lastNs = net.minecraft.util.Util.getNanos();
      lg$target = Math.max(0.0, Math.min(max, t));
   }

   @Override
   public void liquidglass$stepScroll() {
      if (Double.isNaN(lg$target)) return;
      long now = net.minecraft.util.Util.getNanos();
      double dt = lg$lastNs == 0L ? 1.0 / 60.0 : Math.min(0.05, (now - lg$lastNs) / 1.0e9);
      lg$lastNs = now;
      double tgt = Math.max(0.0, Math.min(maxScrollAmount(), lg$target));
      double cur = scrollAmount();
      double next = cur + (tgt - cur) * (1.0 - Math.exp(-dt / LG_TAU));
      boolean done = Math.abs(tgt - next) < 0.35;
      if (done) next = tgt;
      lg$stepping = true;
      try {
         setScrollAmount(next);
      } finally {
         lg$stepping = false;
      }
      if (done) { lg$target = Double.NaN; lg$lastNs = 0L; }
   }
}
