package dev.s1mp1e.o.mixin;

import dev.s1mp1e.o.glass.hook.TabSwitchHook;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** coremod 第 5 組：建立世界的「更多世界選項」切換前，在版面翻過去之前拍快照做交叉淡化。 */
@Mixin(value = CreateWorldScreen.class, priority = 1100)
public abstract class CreateWorldScreenMixin {
    @Inject(method = "setScreen(Z)V", at = @At("HEAD"))
    private void s1mp1e$moreOptions(boolean show, CallbackInfo ci) {
        TabSwitchHook.createWorld((CreateWorldScreen) (Object) this, show);
    }
}
