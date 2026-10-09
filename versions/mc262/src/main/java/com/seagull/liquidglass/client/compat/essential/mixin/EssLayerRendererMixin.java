package com.seagull.liquidglass.client.compat.essential.mixin;

import com.seagull.liquidglass.client.compat.essential.EssentialGlass;
import gg.essential.elementa.renderer.ElementaRenderState;
import gg.essential.util.UDrawContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Essential 1.5 overlay layers (main-menu side buttons, modals, toasts): the layer's texture is blitted into the GUI by an
 * {@code McElementaExtractor} — lay the recorded glass under it just before.
 */
@Pseudo
@Mixin(targets = "gg.essential.gui.overlay.LayerRenderer", remap = false)
public abstract class EssLayerRendererMixin {

   @Inject(method = "render", at = @At(value = "INVOKE",
         target = "Lgg/essential/util/McElementaExtractor;blit(IIIIFFFFLgg/essential/universal/render/UGpuTextureView;Lgg/essential/universal/render/UGpuSampler;ZZLjava/awt/Color;)V"),
         require = 0)
   private void lg$glassUnder(UDrawContext ctx, ElementaRenderState state, CallbackInfo ci) {
      EssentialGlass.beforeComposite(ctx.getMc(), state);
   }
}
