package dev.s1mp1e.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.device.GpuSurface;
import dev.s1mp1e.client.perf.FastSync;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * {@link FastSync}: the frame loop's swapchain acquire. A skipped acquire leaves the surface un-acquired, and
 * Minecraft's own {@code isAcquired()} checks then skip the blit and the present for this frame only.
 */
@Mixin(Minecraft.class)
public abstract class FrontendSurfaceFastSyncMixin {
    @WrapOperation(method = "renderFrame(Z)V", at = @At(value = "INVOKE",
            target = "Lcom/mojang/renderpearl/api/device/GpuSurface;acquireNextTexture()V"), require = 0)
    private void s1mp1e$fastSync(GpuSurface surface, Operation<Void> original) {
        if (!FastSync.shouldAcquire()) return;
        original.call(surface);
        FastSync.acquired();
    }
}
