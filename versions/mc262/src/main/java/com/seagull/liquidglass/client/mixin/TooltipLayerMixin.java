package com.seagull.liquidglass.client.mixin;

import com.seagull.liquidglass.client.render.TooltipGlass;
import com.seagull.liquidglass.client.render.TooltipLayer;
import dev.s1mp1e.client.GuiLayerProbe;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every tooltip gets a stratum of its own that is later moved to the top ({@link TooltipLayer}), and the glass card's
 * fade-out ghost is drawn at the very end of the screen's deferred elements, on the same top layer.
 */
@Mixin({GuiGraphicsExtractor.class})
public abstract class TooltipLayerMixin {
   @Inject(method = "tooltip", at = @At("HEAD"))
   private void lg$tooltipBegin(CallbackInfo ci) {
      GuiGraphicsExtractor g = (GuiGraphicsExtractor)(Object)this;
      GuiLayerProbe.tooltipBegin(((GuiGraphicsExtractorAccessor)g).liquidglass$guiRenderState());
      TooltipLayer.begin(g);
   }

   @Inject(method = "tooltip", at = @At("RETURN"))
   private void lg$tooltipEnd(CallbackInfo ci) {
      GuiGraphicsExtractor g = (GuiGraphicsExtractor)(Object)this;
      TooltipLayer.end(g);
      GuiLayerProbe.tooltipEnd(((GuiGraphicsExtractorAccessor)g).liquidglass$guiRenderState());
   }

   /** After the deferred tooltip (if any) ran: fade the card out on the top layer when no tooltip drew this frame. */
   @Inject(method = "extractDeferredElements", at = @At("RETURN"))
   private void lg$tooltipGhost(int mouseX, int mouseY, float delta, CallbackInfo ci) {
      GuiGraphicsExtractor g = (GuiGraphicsExtractor)(Object)this;
      // the dev probe counts the ghost's own quads (card + readability scrim) as tooltip content, like a live tooltip's
      GuiLayerProbe.tooltipBegin(((GuiGraphicsExtractorAccessor)g).liquidglass$guiRenderState());
      TooltipGlass.ghostPass(g);
      GuiLayerProbe.tooltipEnd(((GuiGraphicsExtractorAccessor)g).liquidglass$guiRenderState());
   }
}
