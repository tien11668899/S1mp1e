package dev.s1mp1e.glass.mixin;

import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 1.13.2 stand-in for {@code MinecraftClient.getCurrentFps()} (which only arrived later). The frame rate
 * lives in the private static field {@code currentFps}; expose it with a STATIC accessor (Sponge mixin
 * 0.8.5 supports static {@link Accessor} in interface mixins). {@link dev.s1mp1e.client.module.FpsHudModule}
 * calls this and falls back to parsing {@code MinecraftClient.fpsDebugString} if the accessor is unavailable.
 */
@Mixin(MinecraftClient.class)
public interface MinecraftClientFpsAccessor {
    @Accessor("currentFps")
    static int s1mp1e$currentFps() { throw new AssertionError(); }
}
