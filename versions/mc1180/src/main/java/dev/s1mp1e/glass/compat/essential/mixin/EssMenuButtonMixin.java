package dev.s1mp1e.glass.compat.essential.mixin;

import java.awt.Color;

import dev.s1mp1e.glass.compat.essential.EssentialGlass;
import gg.essential.universal.UMatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Essential's {@code MenuButton} (the side bar on the title / pause menu — 主持世界, 社交, 衣櫃 … — and other "Essential"
 * buttons) draws its bevelled face with its own pipeline; turn the plain and the textured variant into glass capsules.
 */
@Pseudo
@Mixin(targets = "gg.essential.gui.common.MenuButton$Companion", remap = false)
public abstract class EssMenuButtonMixin {

   @Inject(method = "drawButton(Lgg/essential/universal/UMatrixStack;DDDDLjava/awt/Color;Ljava/awt/Color;Ljava/awt/Color;Ljava/awt/Color;ZZZZ)V",
           at = @At("HEAD"), cancellable = true)
   private void lg$glassButton(UMatrixStack stack, double x1, double y1, double x2, double y2, Color background, Color highlight,
                               Color outline, Color shadow, boolean a, boolean b, boolean c, boolean d, CallbackInfo ci) {
      if (EssentialGlass.menuButton(stack, x1, y1, x2, y2, background)) ci.cancel();
   }

   @Inject(method = "drawTexturedButton(Lgg/essential/universal/UMatrixStack;DDDDLjava/awt/Color;ZZLgg/essential/util/image/bitmap/Bitmap;)V",
           at = @At("HEAD"), cancellable = true)
   private void lg$glassTextured(UMatrixStack stack, double x1, double y1, double x2, double y2, Color color, boolean a, boolean b,
                                 @Coerce Object bitmap, CallbackInfo ci) {
      if (EssentialGlass.menuButton(stack, x1, y1, x2, y2, color)) ci.cancel();
   }

   /** Essential 1.5 (Elementa 774): the same buttons, extracted — immediate on 1.21.1, through the extractor's stack. */
   @Inject(method = "extractButton(Lgg/essential/elementa/renderer/ElementaExtractor;DDDDLjava/awt/Color;Ljava/awt/Color;Ljava/awt/Color;Ljava/awt/Color;ZZZZ)V",
         at = @At("HEAD"), cancellable = true, require = 0)
   private void lg$glassButtonExtract(@org.spongepowered.asm.mixin.injection.Coerce Object extractor, double x1, double y1,
         double x2, double y2, Color background, Color highlight, Color outlineHighlight, Color outline, boolean a, boolean b,
         boolean c, boolean d, CallbackInfo ci) {
      if (EssentialGlass.menuButtonExtract(extractor, x1, y1, x2, y2, background)) { ci.cancel(); return; }
      UMatrixStack stack = EssentialGlass.stackOf(extractor);
      if (stack != null && EssentialGlass.menuButton(stack, x1, y1, x2, y2, background)) ci.cancel();
   }

   @Inject(method = "extractTexturedButton(Lgg/essential/elementa/renderer/ElementaExtractor;DDDDLjava/awt/Color;ZZLgg/essential/util/image/bitmap/Bitmap;)V",
         at = @At("HEAD"), cancellable = true, require = 0)
   private void lg$glassTexturedExtract(@org.spongepowered.asm.mixin.injection.Coerce Object extractor, double x1, double y1,
         double x2, double y2, Color color, boolean a, boolean b, @org.spongepowered.asm.mixin.injection.Coerce Object bitmap,
         CallbackInfo ci) {
      if (EssentialGlass.menuButtonExtract(extractor, x1, y1, x2, y2, color)) { ci.cancel(); return; }
      UMatrixStack stack = EssentialGlass.stackOf(extractor);
      if (stack != null && EssentialGlass.menuButton(stack, x1, y1, x2, y2, color)) ci.cancel();
   }
}
