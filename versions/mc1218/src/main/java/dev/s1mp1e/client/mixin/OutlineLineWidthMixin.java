package dev.s1mp1e.client.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.s1mp1e.client.module.BlockOutlineModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * BlockOutline line width on 1.21.6-1.21.8: the {@code lines} render type sets {@code RenderSystem.lineWidth} (vanilla's
 * window-scaled 2.5 px) when its batch draws. Only while {@code BlockOutlineMixin} has the outline's own batch open
 * ({@link BlockOutlineModule#outlineWidthScope}) is that width replaced by the module's; every other line keeps it.
 */
@Mixin(RenderSystem.class)
public abstract class OutlineLineWidthMixin {
    @ModifyVariable(method = "lineWidth", at = @At("HEAD"), argsOnly = true)
    private static float s1mp1e$outlineWidth(float width) {
        return BlockOutlineModule.outlineWidthScope ? BlockOutlineModule.lineWidth(width) : width;
    }
}
