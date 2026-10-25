package dev.s1mp1e.client.mixin;

import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 26.3's Vulkan backend turns on a GPU crash-breadcrumb extension whenever the driver offers one — on NVIDIA that is
 * VK_AMD_buffer_marker (the NVIDIA driver exposes it), and the command encoder then writes markers into the frame's
 * command buffers so a device-lost crash can be located. They only ever help after a crash and cost GPU writes and
 * ordering every frame. Experiment, off by default (markers stay as vanilla has them): interleaved runs showed no
 * measurable difference. {@code S1MP1E_VK_MARKERS=0} leaves both breadcrumb feature sets off; the backend then uses its
 * no-op checkpoint storage.
 */
@Mixin(FeatureSet.class)
public abstract class VulkanMarkersOffMixin {
    @Unique private static final boolean KEEP = !"0".equals(System.getenv("S1MP1E_VK_MARKERS"));

    @Inject(method = "checkCondition", at = @At("HEAD"), cancellable = true, require = 0)
    private void s1mp1e$noBreadcrumbs(VkPhysicalDevice device, CallbackInfoReturnable<Boolean> cir) {
        if (KEEP) return;
        String name = ((FeatureSet) (Object) this).name();
        if ("AMD Buffer Marker".equals(name) || "NV Diagnostic Checkpoint".equals(name)) cir.setReturnValue(false);
    }
}
