package dev.s1mp1e.o.mixin;

import dev.s1mp1e.o.client.gui.SettingsShell;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.SkinCustomizationScreen;
import net.minecraft.client.gui.screen.SoundsScreen;
import net.minecraft.client.gui.screen.VideoOptionsScreen;
import net.minecraft.client.gui.screen.options.ChatOptionsScreen;
import net.minecraft.client.gui.screen.options.ControlsOptionsScreen;
import net.minecraft.client.gui.screen.options.LanguageOptionsScreen;
import net.minecraft.client.gui.screen.options.OptionsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** coremod 第 1 組「設定頁外殼」：這些設定畫面的 render 開頭交給 SettingsShell（玻璃側邊欄＋卡片列）畫。 */
@Mixin(value = { OptionsScreen.class, VideoOptionsScreen.class, ControlsOptionsScreen.class, LanguageOptionsScreen.class,
        ChatOptionsScreen.class, SoundsScreen.class, SkinCustomizationScreen.class }, priority = 1100)
public abstract class SettingsScreensMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$shell(int mx, int my, float pt, CallbackInfo ci) {
        if (SettingsShell.render((Screen) (Object) this, mx, my, pt)) ci.cancel();
    }
}
