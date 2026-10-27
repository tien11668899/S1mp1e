package dev.s1mp1e.glass.compat.essential.mixin;

import dev.s1mp1e.glass.compat.essential.EssentialGlass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Essential 1.5 screens: {@code UScreen} composites the rendered texture here — the recorded glass goes under it. */
@Pseudo
@Mixin(targets = "gg.essential.universal.UScreen", remap = false)
public abstract class EssUScreenCompositeMixin {

   @Inject(method = "drawImmediate", at = @At("HEAD"), require = 0)
   private void lg$glassUnder(@Coerce Object stack, @Coerce Object texture, CallbackInfo ci) {
      EssentialGlass.beforeComposite(texture);
   }
}
