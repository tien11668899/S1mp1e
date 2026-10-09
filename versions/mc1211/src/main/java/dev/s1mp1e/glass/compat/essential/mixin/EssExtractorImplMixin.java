package dev.s1mp1e.glass.compat.essential.mixin;

import java.awt.Color;

import dev.s1mp1e.glass.compat.essential.EssentialGlass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Essential 1.5 / Elementa 774: one {@code ElementaExtractorImpl} records one frame of an Essential UI, later rendered into
 * a texture and composited. Fills decide here (glass pieces stay out of the texture); the glass is drawn at the composite.
 * Elementa 774 is not on this module's compile path (1.4's 745 is), so its types are coerced / read reflectively.
 */
@Pseudo
@Mixin(targets = "gg.essential.elementa.renderer.impl.ElementaExtractorImpl", remap = false)
public abstract class EssExtractorImplMixin {

   @Inject(method = "<init>", at = @At("RETURN"), require = 0)
   private void lg$begin(@Coerce Object screenSize, float guiScale, CallbackInfo ci) {
      int w = 0;
      try {
         Class<?> c = screenSize.getClass();
         w = (Integer) c.getMethod("getX2").invoke(screenSize) - (Integer) c.getMethod("getX1").invoke(screenSize);
      } catch (Throwable ignored) { }
      EssentialGlass.beginExtract(w);
   }

   @Inject(method = "fill(IIIILjava/awt/Color;)V", at = @At("HEAD"), cancellable = true, require = 0)
   private void lg$fill(int l, int t, int r, int b, Color color, CallbackInfo ci) {
      if (!EssentialGlass.extracting()) return;
      Color out = EssentialGlass.onFill(l, t, r, b, color);
      if (out == color) return;
      ci.cancel();
      if (out == null) return;
      EssentialGlass.guard = true;
      try {
         this.getClass().getMethod("fill", int.class, int.class, int.class, int.class, Color.class).invoke(this, l, t, r, b, out);
      } catch (Throwable ignored) {
      } finally {
         EssentialGlass.guard = false;
      }
   }

   @Inject(method = "pushScissor(IIII)V", at = @At("HEAD"), require = 0)
   private void lg$pushScissor(int l, int t, int r, int b, CallbackInfo ci) {
      EssentialGlass.pushClip(l, t, r, b);
   }

   @Inject(method = "pushScissorRaw(IIII)V", at = @At("HEAD"), require = 0)
   private void lg$pushScissorRaw(int l, int t, int r, int b, CallbackInfo ci) {
      EssentialGlass.pushClip(l, t, r, b);
   }

   @Inject(method = "popScissor()V", at = @At("HEAD"), require = 0)
   private void lg$popScissor(CallbackInfo ci) {
      EssentialGlass.popClip();
   }

   @Inject(method = "finish", at = @At("RETURN"), require = 0)
   private void lg$end(CallbackInfoReturnable<Object> cir) {
      EssentialGlass.endExtract(cir.getReturnValue());
   }
}
