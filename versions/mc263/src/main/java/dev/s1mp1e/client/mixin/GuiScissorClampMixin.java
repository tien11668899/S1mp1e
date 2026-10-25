package dev.s1mp1e.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.commands.RenderPass;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * GuiRenderer turns a GUI scissor into pixels with the window size of the frame's extracted state; when the window
 * shrinks between extraction and drawing (leaving exclusive fullscreen, a restore to 854x480) that rectangle can reach
 * past the render target, and renderpearl throws ("Scissor … is out of bounds for render area …", 2026-10-24 crash).
 * Clamp it to the target being drawn into instead; inside the target nothing changes.
 */
@Mixin(GuiRenderer.class)
public class GuiScissorClampMixin {
    @WrapOperation(method = "enableScissor", at = @At(value = "INVOKE",
            target = "Lcom/mojang/renderpearl/api/commands/RenderPass;enableScissor(IIII)V"), require = 0)
    private void s1mp1e$clampScissor(RenderPass pass, int x, int y, int w, int h, Operation<Void> original) {
        RenderTarget t = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        int tw = t.width, th = t.height;
        int x0 = Math.clamp(x, 0, tw), y0 = Math.clamp(y, 0, th);
        int x1 = Math.clamp((long) x + w, 0, tw), y1 = Math.clamp((long) y + h, 0, th);
        original.call(pass, x0, y0, Math.max(0, x1 - x0), Math.max(0, y1 - y0));
    }
}
