package dev.s1mp1e.glass.compat.essential.mixin;

import dev.s1mp1e.glass.compat.essential.EssentialGlass;
import gg.essential.elementa.components.Window;
import gg.essential.universal.UMatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * One Elementa {@code Window.draw} = one frame of an Essential UI (its screens, its overlays, its main-menu side bar all
 * draw through a Window), so the glass classification ({@link EssentialGlass}) is active exactly while one paints.
 */
@Pseudo
@Mixin(value = Window.class, remap = false)
public abstract class EssWindowFrameMixin {

   @Inject(method = "draw(Lgg/essential/universal/UMatrixStack;)V", at = @At("HEAD"))
   private void s1mp1e$begin(UMatrixStack stack, CallbackInfo ci) {
      EssentialGlass.begin(this);
   }

   @Inject(method = "draw(Lgg/essential/universal/UMatrixStack;)V", at = @At("RETURN"))
   private void s1mp1e$end(UMatrixStack stack, CallbackInfo ci) {
      EssentialGlass.end();
   }
}
