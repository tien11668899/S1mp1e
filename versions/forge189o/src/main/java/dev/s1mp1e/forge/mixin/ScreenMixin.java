package dev.s1mp1e.forge.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Forge 在 GuiScreen.handleInput 的 Mouse/KeyboardInputEvent.Pre（可取消）／Post。 */
@Mixin(value = Screen.class, priority = 1050)
public abstract class ScreenMixin {
    @Shadow protected Minecraft minecraft;

    @WrapOperation(method = "handleInputs", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screen/Screen;handleMouse()V"))
    private void s1f$mouse(Screen self, Operation<Void> op) {
        if (MinecraftForge.EVENT_BUS.post(new GuiScreenEvent.MouseInputEvent.Pre(self))) return;
        op.call(self);
        if (self.equals(this.minecraft.screen)) MinecraftForge.EVENT_BUS.post(new GuiScreenEvent.MouseInputEvent.Post(self));
    }

    @WrapOperation(method = "handleInputs", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screen/Screen;handleKeyboard()V"))
    private void s1f$key(Screen self, Operation<Void> op) {
        if (MinecraftForge.EVENT_BUS.post(new GuiScreenEvent.KeyboardInputEvent.Pre(self))) return;
        op.call(self);
        if (self.equals(this.minecraft.screen)) MinecraftForge.EVENT_BUS.post(new GuiScreenEvent.KeyboardInputEvent.Post(self));
    }
}
