package dev.s1mp1e.o.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.s1mp1e.o.glass.hook.GlassTabList;
import dev.s1mp1e.o.glass.hook.HudMotionHook;
import net.minecraft.client.gui.overlay.PlayerTabOverlay;
import net.minecraft.client.render.TextRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * coremod「GuiPlayerTabOverlay」：所有 fill 交給 GlassTabList（高的頭/清單/尾→玻璃板、短的列條→淡化）；
 * render／renderPing／renderDisplayScore 裡的顏色與文字乘上 Tab 清單的淡入淡出 alpha（第 7 組）。
 */
@Mixin(value = PlayerTabOverlay.class, priority = 1100)
public abstract class PlayerTabOverlayMixin {
    @WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/overlay/PlayerTabOverlay;fill(IIIII)V"))
    private void s1mp1e$rect(int x0, int y0, int x1, int y1, int c, Operation<Void> op) {
        GlassTabList.rect(x0, y0, x1, y1, c);
    }

    @WrapOperation(method = { "render", "renderPing", "renderDisplayScore" }, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/render/platform/GlStateManager;color4f(FFFF)V"))
    private void s1mp1e$color(float r, float g, float b, float a, Operation<Void> op) {
        HudMotionHook.tabColor(r, g, b, a);
    }

    @WrapOperation(method = { "render", "renderPing", "renderDisplayScore" }, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/render/TextRenderer;drawWithShadow(Ljava/lang/String;FFI)I"))
    private int s1mp1e$text(TextRenderer fr, String s, float x, float y, int c, Operation<Integer> op) {
        return HudMotionHook.tabText(fr, s, x, y, c);
    }
}
