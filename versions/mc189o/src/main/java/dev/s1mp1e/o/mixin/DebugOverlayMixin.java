package dev.s1mp1e.o.mixin;

import dev.s1mp1e.o.glass.hook.DebugCardHook;
import net.minecraft.client.gui.overlay.DebugOverlay;
import net.minecraft.client.render.TextRenderer;
import net.minecraft.client.render.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * allglass #15 — the F3 debug overlay as grouped glass plates (one rounded 0x60 scrim per run of consecutive lines,
 * no per-line seam) instead of vanilla's grey strip per line.
 *
 * <p>Vanilla {@code DebugOverlay} draws the two lists itself in {@code drawGameInfo()} (left) and
 * {@code drawSystemInfo(Window)} (right): a {@code GuiElement.fill} per non-empty line then the text. We cancel each
 * at HEAD and let {@link DebugCardHook} lay out the plates (vanilla's exact {@code top = 2 + fontHeight*i} maths) and
 * draw the text on top. On Forge this is instead done through {@code renderHUDText} — here the game is vanilla, so the
 * patch lives on the vanilla path (记忆：Ornithe 跑原版，F3 由原版 debug HUD 自己畫).
 */
@Mixin(value = DebugOverlay.class, priority = 1100)
public abstract class DebugOverlayMixin {

    @Shadow protected abstract List<String> getGameInfo();

    @Shadow protected abstract List<String> getSystemInfo();

    @Shadow @org.spongepowered.asm.mixin.Final private TextRenderer textRenderer;

    @Inject(method = "drawGameInfo", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$left(CallbackInfo ci) {
        if (DebugCardHook.draw(this.textRenderer, this.getGameInfo(), false, 0)) ci.cancel();
    }

    @Inject(method = "drawSystemInfo", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$right(Window window, CallbackInfo ci) {
        if (DebugCardHook.draw(this.textRenderer, this.getSystemInfo(), true, window.getWidth())) ci.cancel();
    }
}
