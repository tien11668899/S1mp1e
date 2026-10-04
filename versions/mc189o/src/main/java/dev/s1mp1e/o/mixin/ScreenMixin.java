package dev.s1mp1e.o.mixin;

import dev.s1mp1e.o.client.gui.SettingsShell;
import dev.s1mp1e.o.event.GuiScreenEvent;
import dev.s1mp1e.o.event.MinecraftForge;
import dev.s1mp1e.o.glass.asm.MenuBackdropHook;
import dev.s1mp1e.o.glass.asm.TooltipHook;
import java.util.List;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.render.TextRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Screen：Forge 的 BackgroundDrawnEvent；coremod 的泥土背景→模糊、工具提示→玻璃、設定頁外殼的點擊/滾輪閘門。 */
@Mixin(value = Screen.class, priority = 1100)
public abstract class ScreenMixin {
    @Shadow protected TextRenderer textRenderer;

    @Inject(method = "renderBackground(I)V", at = @At("TAIL"))
    private void s1mp1e$bgDrawn(int offset, CallbackInfo ci) {
        MinecraftForge.EVENT_BUS.post(new GuiScreenEvent.BackgroundDrawnEvent((Screen) (Object) this));
    }

    @Inject(method = "drawBackgroundTexture", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$backdrop(int tint, CallbackInfo ci) {
        if (MenuBackdropHook.draw((Screen) (Object) this, tint)) ci.cancel();
    }

    @Inject(method = "renderTooltip(Ljava/util/List;II)V", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$tooltip(List<String> lines, int x, int y, CallbackInfo ci) {
        if (TooltipHook.draw((Screen) (Object) this, lines, x, y, this.textRenderer)) {
            // Argentum 在同一個開頭開了漸層批次、要畫到文字才關；我們取消了原方法，要替它關掉（見 ArgentumCompat）
            dev.s1mp1e.o.util.ArgentumCompat.endGradientBatch();
            ci.cancel();
        }
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$shellClick(int mx, int my, int button, CallbackInfo ci) {
        if (SettingsShell.mouseClicked((Screen) (Object) this, mx, my, button)) ci.cancel();
    }

    @Inject(method = "handleMouse", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$shellWheel(CallbackInfo ci) {
        if (SettingsShell.handleWheel((Screen) (Object) this)) ci.cancel();
    }
}
