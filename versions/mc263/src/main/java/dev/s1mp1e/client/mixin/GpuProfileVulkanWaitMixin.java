package dev.s1mp1e.client.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import dev.s1mp1e.client.perf.GpuProfiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** {@link GpuProfiler}: render-thread time spent waiting for earlier submissions to finish (Vulkan backend). */
@Mixin(VulkanCommandEncoder.class)
public abstract class GpuProfileVulkanWaitMixin {
    @Unique private static long s1mp1e$t0;

    @Inject(method = "awaitSubmitCompletion", at = @At("HEAD"), require = 0)
    private void s1mp1e$waitHead(long a, long b, CallbackInfoReturnable<Boolean> cir) { if (GpuProfiler.ON) s1mp1e$t0 = System.nanoTime(); }

    @Inject(method = "awaitSubmitCompletion", at = @At("RETURN"), require = 0)
    private void s1mp1e$waitTail(long a, long b, CallbackInfoReturnable<Boolean> cir) { if (GpuProfiler.ON) GpuProfiler.waitNs += System.nanoTime() - s1mp1e$t0; }
}
