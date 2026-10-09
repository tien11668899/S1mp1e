package com.seagull.liquidglass.client.compat.essential.mixin;

import java.awt.Color;

import com.seagull.liquidglass.client.compat.essential.EssentialGlass;
import gg.essential.elementa.renderer.ElementaExtractor;
import gg.essential.elementa.renderer.ElementaRenderState;
import gg.essential.elementa.renderer.impl.Rect;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Essential 1.5 / Elementa 774: one {@code ElementaExtractorImpl} records one frame of an Essential UI (screen or overlay
 * layer) as elements, later rendered into a texture and composited. Its solid fills go through the same palette rules as
 * the old {@code UIBlock} hook: glass pieces are left out of the texture (transparent there) and recorded, to be laid
 * under the texture when it is composited. Pixel coordinates, own scissor stack.
 */
@Pseudo
@Mixin(targets = "gg.essential.elementa.renderer.impl.ElementaExtractorImpl", remap = false)
public abstract class EssExtractorImplMixin {

   @Inject(method = "<init>", at = @At("RETURN"), require = 0)
   private void lg$begin(Rect screenSize, float guiScale, CallbackInfo ci) {
      EssentialGlass.beginExtract(guiScale, screenSize.getX2() - screenSize.getX1(), screenSize.getY2() - screenSize.getY1());
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
         ((ElementaExtractor) (Object) this).fill(l, t, r, b, out);
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

   @Inject(method = "finish()Lgg/essential/elementa/renderer/ElementaRenderState;", at = @At("RETURN"), require = 0)
   private void lg$end(CallbackInfoReturnable<ElementaRenderState> cir) {
      EssentialGlass.endExtract(cir.getReturnValue());
   }
}
