package dev.s1mp1e.client.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanGpuSurface;
import dev.s1mp1e.client.perf.FastSync;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** {@link FastSync}: how long each vkAcquireNextImageKHR took (the signal that the display path is the bottleneck). */
@Mixin(VulkanGpuSurface.class)
public abstract class VulkanFastSyncMixin {
    @Unique private static long s1mp1e$acquireStart;

    @Inject(method = "acquireNextTexture", at = @At("HEAD"), require = 0)
    private void s1mp1e$acquireStart(CallbackInfo ci) { s1mp1e$acquireStart = System.nanoTime(); }

    @Inject(method = "acquireNextTexture", at = @At("RETURN"), require = 0)
    private void s1mp1e$acquireEnd(CallbackInfo ci) { FastSync.lastAcquireNs = System.nanoTime() - s1mp1e$acquireStart; }
}
