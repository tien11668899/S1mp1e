package dev.s1mp1e.client.mixin;

import dev.s1mp1e.client.module.DynamicIslandModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 有游標的畫面（聊天等）開著時，左鍵點在靈動島上：交給靈動島（播放控制）並吃掉這次點擊，
 * 不讓它點穿到後面的畫面。沒點到靈動島就完全不影響。
 */
@Mixin(MouseHandler.class)
public class IslandClickMixin {
    @Inject(method = "onButton(JLnet/minecraft/client/input/MouseButtonInfo;I)V", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$island(long handle, MouseButtonInfo info, int action, CallbackInfo ci) {
        if (action != 1 || info.button() != 0) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() == null) return;
        MouseHandler self = (MouseHandler) (Object) this;
        double gx = self.xpos() * mc.getWindow().getGuiScaledWidth() / Math.max(1, mc.getWindow().getScreenWidth());
        double gy = self.ypos() * mc.getWindow().getGuiScaledHeight() / Math.max(1, mc.getWindow().getScreenHeight());
        if (DynamicIslandModule.click(gx, gy)) ci.cancel();
    }
}
