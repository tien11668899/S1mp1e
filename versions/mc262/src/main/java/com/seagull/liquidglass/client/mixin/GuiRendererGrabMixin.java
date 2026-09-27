package com.seagull.liquidglass.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.seagull.liquidglass.client.render.GlassPipeline;
import com.seagull.liquidglass.client.render.TooltipLayer;
import dev.s1mp1e.client.GuiLayerProbe;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * GuiRenderer hooks: (1) the world-only glass backdrop grab + the tooltip-strata promotion at render HEAD (extraction is
 * complete, nothing is prepared yet); (2) the top-layer split — {@code addElementToMesh} records the draw index of the
 * first tooltip card and {@code draw()}'s {@code executeDrawRange} calls are split there so the framebuffer (every GUI
 * layer below the tooltip) can be copied into the card's backdrop between the two passes. See {@link TooltipLayer}.
 */
@Mixin({GuiRenderer.class})
public class GuiRendererGrabMixin {
   @Shadow
   @Final
   private GuiRenderState renderState;

   @Shadow
   @Final
   private List<?> draws;

   @Inject(
      method = {"render()V"},
      at = {@At("HEAD")}
   )
   private void lg$grabCleanBackdrop(CallbackInfo ci) {
      GlassPipeline.grabBackdrop();
      // Tooltip strata to the END of the strata list: drawn after everything else in the frame.
      TooltipLayer.promote(this.renderState);
      // Dev-only layer probe (inert unless the tooltips DevShot sweep armed it): verifies the FINAL order.
      GuiLayerProbe.onGuiRender(this.renderState);
   }

   @Inject(method = "addElementToMesh", at = @At("HEAD"))
   private void lg$markTooltipSplit(GuiElementRenderState element, CallbackInfo ci) {
      TooltipLayer.onAddElement(element, this.draws.size());
   }

   @WrapOperation(
      method = "draw",
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/gui/render/GuiRenderer;executeDrawRange(Ljava/util/function/Supplier;Lcom/mojang/blaze3d/pipeline/RenderTarget;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;II)V"
      )
   )
   private void lg$splitAtTooltip(GuiRenderer self, Supplier<String> label, RenderTarget target, GpuBufferSlice transforms,
                                  int from, int to, Operation<Void> original) {
      int split = TooltipLayer.splitDrawIndex();
      if (split < from || split >= to) {
         original.call(self, label, target, transforms, from, to);
         return;
      }
      if (split > from) {
         original.call(self, label, target, transforms, from, split);   // every layer below the tooltip
      }
      TooltipLayer.grabAtSplit(this.draws.size());                       // framebuffer -> tooltip card backdrop
      original.call(self, label, target, transforms, split, to);        // the tooltip (and nothing else: it is last)
   }
}
