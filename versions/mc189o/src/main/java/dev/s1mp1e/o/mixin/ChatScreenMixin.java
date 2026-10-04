package dev.s1mp1e.o.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.s1mp1e.o.glass.hook.ChatCloseHook;
import dev.s1mp1e.o.glass.hook.GlassChatHud;
import net.minecraft.client.gui.screen.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** coremod「GuiChat」：只把輸入列的底（render 裡第一個 fill）換成玻璃；關閉時記下輸入框文字給淡出用。 */
@Mixin(value = ChatScreen.class, priority = 1100)
public abstract class ChatScreenMixin {
    @WrapOperation(method = "render", at = @At(value = "INVOKE", ordinal = 0, target = "Lnet/minecraft/client/gui/screen/ChatScreen;fill(IIIII)V"))
    private void s1mp1e$input(int x0, int y0, int x1, int y1, int c, Operation<Void> op) {
        GlassChatHud.inputRect(x0, y0, x1, y1, c);
    }

    @Inject(method = "removed", at = @At("HEAD"))
    private void s1mp1e$closed(CallbackInfo ci) {
        ChatCloseHook.closed((ChatScreen) (Object) this);
    }
}
