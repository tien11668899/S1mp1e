package com.seagull.liquidglass.client.mixin;

import com.seagull.liquidglass.client.render.TooltipLayer;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Clears {@link TooltipLayer}'s per-frame state whenever the GUI render state is reset (start of extraction and end of
 *  GuiRenderer.render), so a frame whose GUI is extracted but never drawn cannot leak stale tooltip strata/cards. */
@Mixin({GuiRenderState.class})
public abstract class GuiRenderStateResetMixin {
   @Inject(method = "reset", at = @At("HEAD"))
   private void liquidglass$tooltipLayerReset(CallbackInfo ci) {
      TooltipLayer.frameReset();
   }
}
