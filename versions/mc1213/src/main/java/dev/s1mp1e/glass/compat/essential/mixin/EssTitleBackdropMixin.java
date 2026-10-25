package dev.s1mp1e.glass.compat.essential.mixin;

import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Essential's overlays (its main-menu modals: host a world, account, …) composite over the vanilla title screen, after its
 * buttons. Without a clean backdrop their glass would re-grab the screen there and show the title buttons through the
 * modal. Grab right after the panorama, before the logo and buttons — the Essential glass reuses this fresh backdrop.
 * Registered only with Essential (this config), so the title screen is untouched otherwise.
 */
@Mixin(TitleScreen.class)
public abstract class EssTitleBackdropMixin {

   @Inject(method = "render", at = @At(value = "INVOKE",
         target = "Lnet/minecraft/client/gui/screen/TitleScreen;renderPanoramaBackground(Lnet/minecraft/client/gui/DrawContext;F)V",
         shift = At.Shift.AFTER), require = 0)
   private void lg$cleanBackdrop(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
      context.draw();
      SceneCapture.grabNow();
   }
}
