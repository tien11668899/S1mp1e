package dev.s1mp1e.forge.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Forge 在 GuiScreen 的 InitGuiEvent.Pre/Post（setWorldAndResolution）與 ActionPerformedEvent.Pre/Post（按鈕點擊）。 */
@Mixin(value = Screen.class, priority = 1050)
public abstract class ScreenInitMixin {
    @Shadow protected List<ButtonWidget> buttons;
    @Shadow protected Minecraft minecraft;

    @Unique private boolean s1f$skipInit;
    @Unique private ButtonWidget s1f$pressed;

    @WrapOperation(method = "init(Lnet/minecraft/client/Minecraft;II)V", at = @At(value = "INVOKE", target = "Ljava/util/List;clear()V"))
    private void s1f$initPre(List<?> list, Operation<Void> op) {
        s1f$skipInit = MinecraftForge.EVENT_BUS.post(new GuiScreenEvent.InitGuiEvent.Pre((Screen) (Object) this, this.buttons));
        if (!s1f$skipInit) op.call(list);
    }

    @WrapOperation(method = "init(Lnet/minecraft/client/Minecraft;II)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screen/Screen;init()V"))
    private void s1f$initBody(Screen self, Operation<Void> op) {
        if (!s1f$skipInit) op.call(self);
        s1f$skipInit = false;
    }

    @Inject(method = "init(Lnet/minecraft/client/Minecraft;II)V", at = @At("TAIL"))
    private void s1f$initPost(Minecraft mc, int w, int h, CallbackInfo ci) {
        MinecraftForge.EVENT_BUS.post(new GuiScreenEvent.InitGuiEvent.Post((Screen) (Object) this, this.buttons));
    }

    /** 按到按鈕時發 Pre；取消＝這顆不算按到 */
    @WrapOperation(method = "mouseClicked", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/widget/ButtonWidget;mouseClicked(Lnet/minecraft/client/Minecraft;II)Z"))
    private boolean s1f$actionPre(ButtonWidget b, Minecraft mc, int mx, int my, Operation<Boolean> op) {
        if (!op.call(b, mc, mx, my)) return false;
        GuiScreenEvent.ActionPerformedEvent.Pre e = new GuiScreenEvent.ActionPerformedEvent.Pre((Screen) (Object) this, b, this.buttons);
        if (MinecraftForge.EVENT_BUS.post(e)) return false;
        s1f$pressed = e.button;
        return true;
    }

    @WrapOperation(method = "mouseClicked", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screen/Screen;buttonClicked(Lnet/minecraft/client/gui/widget/ButtonWidget;)V"))
    private void s1f$actionPost(Screen self, ButtonWidget b, Operation<Void> op) {
        ButtonWidget used = s1f$pressed != null ? s1f$pressed : b;
        s1f$pressed = null;
        op.call(self, used);
        if (self.equals(this.minecraft.screen)) MinecraftForge.EVENT_BUS.post(new GuiScreenEvent.ActionPerformedEvent.Post(self, used, this.buttons));
    }
}
