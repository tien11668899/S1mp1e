package com.seagull.liquidglass.client.mixin;

import dev.s1mp1e.client.gui.GuiAlpha;
import net.minecraft.client.renderer.state.gui.BlitRenderState;
import net.minecraft.client.renderer.state.gui.ColoredRectangleRenderState;
import net.minecraft.client.renderer.state.gui.GuiTextRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link GuiAlpha}: scale the stored colours of the three GUI render states every text / fill / blit is built from, at
 * the RETURN of each constructor (after the fields are assigned — safe for records, unlike touching arguments before the
 * {@code super()} call). Every constructor of each class is targeted; delegating constructors simply run it twice on the
 * canonical path, so the hook only applies when a push is active (no-op at the default 1.0) and scales exactly once:
 * a guard compares against the scale already applied.
 */
public final class GuiAlphaRenderStateMixin {
   private GuiAlphaRenderStateMixin() {}

   @Mixin(GuiTextRenderState.class)
   public abstract static class Text {
      @Shadow @Final @Mutable private int color;
      @Shadow @Final @Mutable private int backgroundColor;

      @Inject(method = "<init>", at = @At("RETURN"))
      private void lg$alpha(CallbackInfo ci) {
         if (GuiAlpha.current() >= 0.999F) return;
         this.color = GuiAlpha.apply(this.color);
         this.backgroundColor = GuiAlpha.apply(this.backgroundColor);
      }
   }

   @Mixin(ColoredRectangleRenderState.class)
   public abstract static class Rect {
      @Shadow @Final @Mutable private int col1;
      @Shadow @Final @Mutable private int col2;

      /** Canonical constructor only (the short one delegates to it), so the colours are scaled exactly once. */
      @Inject(
         method = "<init>(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/client/gui/render/TextureSetup;Lorg/joml/Matrix3x2fc;IIIIIILnet/minecraft/client/gui/navigation/ScreenRectangle;Lnet/minecraft/client/gui/navigation/ScreenRectangle;)V",
         at = @At("RETURN"))
      private void lg$alpha(CallbackInfo ci) {
         if (GuiAlpha.current() >= 0.999F) return;
         this.col1 = GuiAlpha.apply(this.col1);
         this.col2 = GuiAlpha.apply(this.col2);
      }
   }

   @Mixin(BlitRenderState.class)
   public abstract static class Blit {
      @Shadow @Final @Mutable private int color;

      /** Canonical constructor only (the short one delegates to it). */
      @Inject(
         method = "<init>(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/client/gui/render/TextureSetup;Lorg/joml/Matrix3x2fc;IIIIFFFFILnet/minecraft/client/gui/navigation/ScreenRectangle;Lnet/minecraft/client/gui/navigation/ScreenRectangle;)V",
         at = @At("RETURN"))
      private void lg$alpha(CallbackInfo ci) {
         if (GuiAlpha.current() >= 0.999F) return;
         this.color = GuiAlpha.apply(this.color);
      }
   }
}
