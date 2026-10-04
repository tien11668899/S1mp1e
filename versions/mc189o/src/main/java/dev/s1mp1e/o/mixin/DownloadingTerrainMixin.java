package dev.s1mp1e.o.mixin;

import dev.s1mp1e.o.glass.hook.LoadingHook;
import net.minecraft.client.gui.screen.DownloadingTerrainScreen;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** coremod 第 10 組載入卡：下載地形畫面在泥土背景之後畫玻璃卡。 */
@Mixin(value = DownloadingTerrainScreen.class, priority = 1100)
public abstract class DownloadingTerrainMixin {
    @Inject(method = "render", at = @At(value = "INVOKE", ordinal = 0, target = "Lnet/minecraft/client/gui/screen/DownloadingTerrainScreen;drawBackgroundTexture(I)V", shift = At.Shift.AFTER))
    private void s1mp1e$card(int mx, int my, float pt, CallbackInfo ci) {
        LoadingHook.card((Screen) (Object) this);
    }
}
