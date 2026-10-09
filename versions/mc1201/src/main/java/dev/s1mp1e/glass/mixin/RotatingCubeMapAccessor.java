package dev.s1mp1e.glass.mixin;

import net.minecraft.client.gui.RotatingCubeMapRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The panorama's current angles (private in 1.20.1), so the title screen and the menu backdrop share one rotation. */
@Mixin(RotatingCubeMapRenderer.class)
public interface RotatingCubeMapAccessor {
   @Accessor("pitch") float s1mp1e$pitch();
   @Accessor("pitch") void s1mp1e$setPitch(float pitch);
   @Accessor("yaw") float s1mp1e$yaw();
   @Accessor("yaw") void s1mp1e$setYaw(float yaw);
}
