package dev.s1mp1e.o.mixin;

import dev.s1mp1e.o.glass.asm.MenuBackdropHook;
import net.minecraft.client.gui.screen.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** coremod「GuiMainMenu」：全景（renderSkybox）一畫完、logo/按鈕還沒畫之前拍快照，當選單模糊背景。 */
@Mixin(value = TitleScreen.class, priority = 1100)
public abstract class TitleScreenMixin {
    @Inject(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screen/TitleScreen;drawBackground(IIF)V", shift = At.Shift.AFTER))
    private void s1mp1e$panorama(int mx, int my, float pt, CallbackInfo ci) {
        MenuBackdropHook.capturePanorama();
    }
}
