package dev.s1mp1e.glass.mixin;

import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads the client's current frame rate on 1.18.2, where {@code MinecraftClient.getCurrentFps()} does
 * not exist — only {@code private static int currentFps} (and the {@code public String fpsDebugString}).
 * {@link dev.s1mp1e.client.module.FpsHudModule} calls this and falls back to parsing the leading integer
 * of {@code fpsDebugString} if the accessor ever throws.
 */
@Mixin(MinecraftClient.class)
public interface MinecraftClientFpsAccessor {
    @Accessor("currentFps") static int s1mp1e$currentFps() { throw new AssertionError(); }
}
