package dev.s1mp1e.o.mixin;

import dev.s1mp1e.o.glass.hook.LoadingHook;
import net.minecraft.client.gui.screen.ConnectScreen;
import net.minecraft.client.gui.screen.ProgressScreen;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** coremod 第 10 組載入卡：連線/進度畫面在第一個背景呼叫之後畫玻璃卡，原版文字浮在卡上。 */
@Mixin(value = { ConnectScreen.class, ProgressScreen.class }, priority = 1100)
public abstract class LoadingCardMixin {
    @Inject(method = "render", at = {
            // 正式版的 Loom 重映射要知道擁有者：兩個目標類別各寫一個（各自只會對到自己的）
            @At(value = "INVOKE", ordinal = 0, target = "Lnet/minecraft/client/gui/screen/ConnectScreen;renderBackground()V", shift = At.Shift.AFTER),
            @At(value = "INVOKE", ordinal = 0, target = "Lnet/minecraft/client/gui/screen/ProgressScreen;renderBackground()V", shift = At.Shift.AFTER)
    })
    private void s1mp1e$card(int mx, int my, float pt, CallbackInfo ci) {
        LoadingHook.card((Screen) (Object) this);
    }
}
