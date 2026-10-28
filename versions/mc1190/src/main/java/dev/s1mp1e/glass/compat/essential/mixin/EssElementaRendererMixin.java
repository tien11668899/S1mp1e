package dev.s1mp1e.glass.compat.essential.mixin;

import dev.s1mp1e.glass.compat.essential.EssentialGlass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Essential 1.5: a recorded frame is rendered into this texture — its glass follows the texture to the composite. */
@Pseudo
@Mixin(targets = "gg.essential.elementa.renderer.ElementaRenderer", remap = false)
public abstract class EssElementaRendererMixin {

   @Inject(method = "renderToTexture", at = @At("HEAD"), require = 0)
   private void lg$link(@Coerce Object target, int a, int b, int c, int d, int e, int f, @Coerce Object state, CallbackInfo ci) {
      EssentialGlass.linkTexture(target, state);
   }
}
