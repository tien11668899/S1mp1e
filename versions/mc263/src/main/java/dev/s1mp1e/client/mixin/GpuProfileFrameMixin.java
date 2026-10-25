package dev.s1mp1e.client.mixin;

import dev.s1mp1e.client.perf.GpuProfiler;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** {@link GpuProfiler}: the frame's first mark (before the game renderer) and its last (after the blit to the window). */
@Mixin(Minecraft.class)
public abstract class GpuProfileFrameMixin {
    @Inject(method = "renderFrame(Z)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render()V"), require = 0)
    private void s1mp1e$gpuStart(boolean tick, CallbackInfo ci) {
        if (GpuProfiler.ON) GpuProfiler.mark("start");
    }

    @Inject(method = "renderFrame(Z)V", at = @At(value = "INVOKE",
            target = "Lcom/mojang/renderpearl/api/device/GpuSurface;blitFromTexture(Lcom/mojang/renderpearl/api/commands/CommandEncoder;Lcom/mojang/renderpearl/api/textures/GpuTextureView;)V",
            shift = At.Shift.AFTER), require = 0)
    private void s1mp1e$gpuBlit(boolean tick, CallbackInfo ci) {
        if (GpuProfiler.ON) GpuProfiler.mark("blitToWindow");
    }

    /** Every frame ends here, also the ones that render without presenting (FastSync). */
    @Inject(method = "renderFrame(Z)V", at = @At("TAIL"), require = 0)
    private void s1mp1e$gpuEnd(boolean tick, CallbackInfo ci) {
        if (GpuProfiler.ON) GpuProfiler.endFrame();
    }
}
