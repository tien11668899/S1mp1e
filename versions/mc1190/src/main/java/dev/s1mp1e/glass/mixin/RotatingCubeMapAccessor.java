package dev.s1mp1e.glass.mixin;

import net.minecraft.client.gui.RotatingCubeMapRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The panorama's rotation phase (private {@code time} in 1.19.2 — a single float, unlike 1.20.1's pitch/yaw pair), so
 * the title screen and the shared menu backdrop can keep one continuous rotation as the player moves between them.
 */
@Mixin(RotatingCubeMapRenderer.class)
public interface RotatingCubeMapAccessor {
    @Accessor("time") float s1mp1e$time();
    @Accessor("time") void s1mp1e$setTime(float time);
}
