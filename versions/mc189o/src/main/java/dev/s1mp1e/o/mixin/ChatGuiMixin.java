package dev.s1mp1e.o.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.s1mp1e.o.glass.hook.GlassChatHud;
import net.minecraft.client.gui.chat.ChatGui;
import net.minecraft.client.render.TextRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * coremod「GuiNewChat.drawChat」：開頭畫一塊玻璃聊天面板；每行的深色底 fill 交給 GlassChatHud.rect（丟掉底、留捲軸）；
 * 第一個 translate（聊天欄位置）與每行文字交給 GlassChatHud（第 7 組進場動畫）。
 */
@Mixin(value = ChatGui.class, priority = 1100)
public abstract class ChatGuiMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void s1mp1e$panel(int ticks, CallbackInfo ci) {
        GlassChatHud.begin((ChatGui) (Object) this, ticks);
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/chat/ChatGui;fill(IIIII)V"))
    private void s1mp1e$rect(int x0, int y0, int x1, int y1, int c, Operation<Void> op) {
        GlassChatHud.rect(x0, y0, x1, y1, c);
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE", ordinal = 0,
            target = "Lnet/minecraft/client/render/platform/GlStateManager;translatef(FFF)V"))
    private void s1mp1e$translate(float x, float y, float z, Operation<Void> op) {
        GlassChatHud.translate(x, y, z);
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/render/TextRenderer;drawWithShadow(Ljava/lang/String;FFI)I"))
    private int s1mp1e$text(TextRenderer fr, String s, float x, float y, int c, Operation<Integer> op) {
        return GlassChatHud.text(fr, s, x, y, c);
    }
}
