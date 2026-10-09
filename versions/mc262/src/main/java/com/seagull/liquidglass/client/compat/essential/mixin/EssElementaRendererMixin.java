package com.seagull.liquidglass.client.compat.essential.mixin;

import com.seagull.liquidglass.client.compat.essential.EssentialGlass;
import gg.essential.elementa.renderer.ElementaRenderState;
import gg.essential.universal.render.UGpuTextureView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Essential 1.5: a recorded frame is rendered into this texture — its glass now follows the texture to the composite. */
@Pseudo
@Mixin(targets = "gg.essential.elementa.renderer.ElementaRenderer", remap = false)
public abstract class EssElementaRendererMixin {

   @Inject(method = "renderToTexture", at = @At("HEAD"), require = 0)
   private void lg$link(UGpuTextureView target, int a, int b, int c, int d, int e, int f, ElementaRenderState state, CallbackInfo ci) {
      EssentialGlass.linkTexture(target, state);
   }
}
