package dev.s1mp1e.client.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanGpuSurface;
import dev.s1mp1e.client.perf.GpuProfiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** {@link GpuProfiler}: render-thread time spent in the swapchain's acquire and present (Vulkan backend). */
@Mixin(VulkanGpuSurface.class)
public abstract class GpuProfileVulkanMixin {
    @Unique private static long s1mp1e$t0;

    @Inject(method = "acquireNextTexture", at = @At("HEAD"), require = 0)
    private void s1mp1e$acquireHead(CallbackInfo ci) { if (GpuProfiler.ON) s1mp1e$t0 = System.nanoTime(); }

    @Inject(method = "acquireNextTexture", at = @At("RETURN"), require = 0)
    private void s1mp1e$acquireTail(CallbackInfo ci) { if (GpuProfiler.ON) GpuProfiler.acquireNs += System.nanoTime() - s1mp1e$t0; }

    @Inject(method = "present", at = @At("HEAD"), require = 0)
    private void s1mp1e$presentHead(CallbackInfo ci) { if (GpuProfiler.ON) s1mp1e$t0 = System.nanoTime(); }

    @Inject(method = "present", at = @At("RETURN"), require = 0)
    private void s1mp1e$presentTail(CallbackInfo ci) { if (GpuProfiler.ON) GpuProfiler.presentNs += System.nanoTime() - s1mp1e$t0; }
}
