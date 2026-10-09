package com.seagull.liquidglass.client.compat.essential.mixin;

import com.seagull.liquidglass.client.compat.essential.EssentialGlass;
import gg.essential.universal.UMatrixStack;
import gg.essential.universal.UScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Essential's full-screen windows (settings, wardrobe, social …) paint an opaque full-screen background and request the
 * vanilla one only from INSIDE their off-screen draw pass ({@code WindowScreen.onDrawScreen → onDrawBackground}), where the
 * panorama does not come out — so once that opaque background has become a glass window ({@link EssentialGlass}) the
 * window would refract an empty black frame. For such a screen, draw the vanilla backdrop (panorama on the title screen,
 * the world in game; blurred, menu background) in the normal background phase like every vanilla menu, and skip the inner
 * request (a second blur in one frame is an error).
 */
@Pseudo
@Mixin(UScreen.class)
public abstract class EssUScreenBackdropMixin {

   /** Set when this frame's backdrop was already provided in the background phase. */
   @Unique private boolean lg$backdropDone;

   @Inject(method = "extractBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V", at = @At("HEAD"), cancellable = true)
   private void lg$vanillaBackdrop(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
      this.lg$backdropDone = false;
      if (!EssentialGlass.wantsBackdrop(this)) return;
      Screen screen = (Screen) (Object) this;
      EssScreenBgInvoker self = (EssScreenBgInvoker) (Object) this;
      if (screen.isInGameUi()) {
         screen.extractTransparentBackground(g);
      } else {
         if (Minecraft.getInstance().level == null) self.lg$panorama(g, delta);
         self.lg$blur(g);
         self.lg$menuBackground(g);
      }
      this.lg$backdropDone = true;
      ci.cancel();
   }

   @Inject(method = "onDrawBackground(Lgg/essential/universal/UMatrixStack;I)V", at = @At("HEAD"), cancellable = true)
   private void lg$skipInnerBackdrop(UMatrixStack stack, int tint, CallbackInfo ci) {
      if (this.lg$backdropDone) ci.cancel();
   }
}
