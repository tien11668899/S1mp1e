package com.seagull.liquidglass.client.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.joml.Matrix3x2fStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Press feedback for every button (glass buttons, cycle buttons, toggles …): on activation — a click, or Enter/Space
 * while focused — the whole button (glass capsule and label) dips to 95 % around its centre in ~50 ms and springs back
 * over ~0.25 s, like a tap on iOS. Vanilla activates on mouse-down, so this is a quick tap pulse rather than a held
 * state. Implemented as a pose scale around {@code extractWidgetRenderState}, so every layer of the button follows.
 */
@Mixin(AbstractButton.class)
public abstract class ButtonPressPulseMixin {

   @Unique private static final float LG_DEPTH = 0.05F;
   @Unique private static final float LG_DOWN_S = 0.05F;
   @Unique private static final float LG_BACK_TAU = 0.075F;

   @Unique private long lg$pressNs;
   @Unique private boolean lg$scaled;

   @Inject(method = "onClick", at = @At("HEAD"))
   private void lg$pulseOnClick(MouseButtonEvent event, boolean doubleClick, CallbackInfo ci) {
      lg$pressNs = net.minecraft.util.Util.getNanos();
   }

   @Inject(method = "keyPressed", at = @At("RETURN"))
   private void lg$pulseOnKey(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
      if (Boolean.TRUE.equals(cir.getReturnValue())) lg$pressNs = net.minecraft.util.Util.getNanos();
   }

   @Inject(method = "extractWidgetRenderState", at = @At("HEAD"))
   private void lg$pulseBegin(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
      lg$scaled = false;
      if (lg$pressNs == 0L) return;
      float t = (net.minecraft.util.Util.getNanos() - lg$pressNs) / 1.0e9F;
      float dip;
      if (t < LG_DOWN_S) {
         float u = t / LG_DOWN_S;
         dip = u * (2F - u);                                         // ease-out into the press
      } else {
         dip = (float) Math.exp(-(t - LG_DOWN_S) / LG_BACK_TAU);     // spring back, no overshoot
         if (dip < 0.004F) { lg$pressNs = 0L; return; }
      }
      AbstractButton self = (AbstractButton) (Object) this;
      float s = 1F - LG_DEPTH * dip;
      float cx = self.getX() + self.getWidth() / 2F, cy = self.getY() + self.getHeight() / 2F;
      Matrix3x2fStack pose = g.pose();
      pose.pushMatrix();
      pose.translate(cx, cy);
      pose.scale(s, s);
      pose.translate(-cx, -cy);
      lg$scaled = true;
   }

   @Inject(method = "extractWidgetRenderState", at = @At("RETURN"))
   private void lg$pulseEnd(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
      if (!lg$scaled) return;
      lg$scaled = false;
      g.pose().popMatrix();
   }
}
