package com.seagull.liquidglass.client.mixin;

import com.seagull.liquidglass.client.render.TooltipGlass;
import com.seagull.liquidglass.client.render.TooltipLayer;
import dev.s1mp1e.client.GuiLayerProbe;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every tooltip gets a stratum of its own that is later moved to the top ({@link TooltipLayer}), and the glass card's
 * fade-out ghost is drawn at the very end of the screen's deferred elements, on the same top layer.
 */
@Mixin({GuiGraphics.class})
public abstract class TooltipLayerMixin {
   @Inject(method = "renderTooltip", at = @At("HEAD"))   // 26.x: tooltip
   private void lg$tooltipBegin(CallbackInfo ci) {
      GuiGraphics g = (GuiGraphics)(Object)this;
      GuiLayerProbe.tooltipBegin(((GuiGraphicsExtractorAccessor)g).liquidglass$guiRenderState());
      TooltipLayer.begin(g);
   }

   @Inject(method = "renderTooltip", at = @At("RETURN"))
   private void lg$tooltipEnd(CallbackInfo ci) {
      GuiGraphics g = (GuiGraphics)(Object)this;
      TooltipLayer.end(g);
      GuiLayerProbe.tooltipEnd(((GuiGraphicsExtractorAccessor)g).liquidglass$guiRenderState());
   }

   /** After the deferred tooltip (if any) ran: fade the card out on the top layer when no tooltip drew this frame. */
   @Inject(method = "renderDeferredElements", at = @At("RETURN"))   // 1.21.8: renderDeferredTooltip; 26.x: extractDeferredElements
   private void lg$tooltipGhost(CallbackInfo ci) {
      GuiGraphics g = (GuiGraphics)(Object)this;
      // the dev probe counts the ghost's own quads (card + readability scrim) as tooltip content, like a live tooltip's
      GuiLayerProbe.tooltipBegin(((GuiGraphicsExtractorAccessor)g).liquidglass$guiRenderState());
      TooltipGlass.ghostPass(g);
      GuiLayerProbe.tooltipEnd(((GuiGraphicsExtractorAccessor)g).liquidglass$guiRenderState());
   }
}
