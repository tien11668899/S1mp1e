package dev.s1mp1e.client.mixin;

import com.mojang.blaze3d.vulkan.VulkanGpuSurface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Benchmark switches for the Vulkan swapchain, with a log line of what it was created with. At 900+ FPS the render
 * thread waited ~1 ms in vkAcquireNextImageKHR on a third of the frames. {@code S1MP1E_VK_SWAP_IMAGES=n} asks for at
 * least n images (vanilla: max(3, the surface minimum)); {@code S1MP1E_VK_PRESENT=0..3} forces VkPresentModeKHR
 * (0 immediate, 1 mailbox, 2 fifo, 3 fifo relaxed). Unset = vanilla.
 */
@Mixin(VulkanGpuSurface.class)
public abstract class VulkanSwapchainMixin {
    @Unique private static final int IMAGES = parse(System.getenv("S1MP1E_VK_SWAP_IMAGES"), 8);
    @Unique private static final int PRESENT = parse(System.getenv("S1MP1E_VK_PRESENT"), 3) - 1;   // -1 = vanilla

    @Unique
    private static int parse(String s, int max) {
        try { return s == null ? 0 : Math.max(0, Math.min(max, Integer.parseInt(s.trim()))) + (max == 3 ? 1 : 0); }
        catch (NumberFormatException e) { return 0; }
    }

    @ModifyArg(method = "configure", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;minImageCount(I)Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;"), require = 0)
    private int s1mp1e$images(int n) {
        int images = Math.max(n, IMAGES);
        System.out.println("[S1mp1e] Vulkan swapchain: min images " + images + " (vanilla " + n + ")");
        return images;
    }

    @ModifyArg(method = "configure", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;presentMode(I)Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;"), require = 0)
    private int s1mp1e$presentMode(int mode) {
        int m = PRESENT >= 0 ? PRESENT : mode;
        dev.s1mp1e.client.perf.FastSync.presentMode = m;
        System.out.println("[S1mp1e] Vulkan swapchain: present mode " + m + " (vanilla " + mode + "; 0 immediate, 1 mailbox, 2 fifo, 3 fifo relaxed)");
        return m;
    }
}
