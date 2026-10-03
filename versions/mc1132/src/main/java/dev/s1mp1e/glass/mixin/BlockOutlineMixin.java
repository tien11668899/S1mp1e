package dev.s1mp1e.glass.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.platform.GlStateManager;
import dev.s1mp1e.client.module.BlockOutlineModule;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.util.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * BlockOutline module (H1): colour / chroma / line width / translucent fill of the targeted block's selection box.
 *
 * <p><b>1.13.2.</b> There are no render layers or vertex consumers yet (1.15+): the selection box is drawn
 * immediately by {@code WorldRenderer.drawHighlightedBlockOutline(Camera, HitResult, int)} (javap-read) — it sets
 * {@code GlStateManager.lineWidth(max(2.5, fbWidth / 1920 * 2.5))}, switches texturing and the depth mask off and
 * calls the static {@code drawShapeOutline(shape, dx, dy, dz, 0, 0, 0, 0.4)}. So:
 * <ul>
 *   <li>the width is the argument of that one {@code lineWidth} call (no {@code RenderPhase.LineWidth} to patch);</li>
 *   <li>the colour and the fill are one wrap around {@code drawShapeOutline}: the fill is drawn first (same matrices,
 *       depth-tested, no depth write), the GL state vanilla set up for the lines is put back, then the outline is
 *       drawn with the module's colour.</li>
 * </ul>
 * With the module off both hooks pass vanilla's values through untouched. A pure recolour / restyle of what vanilla
 * already draws — fair play.
 */
@Mixin(WorldRenderer.class)
public abstract class BlockOutlineMixin {

    @ModifyArg(method = "drawBlockOutline",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/GlStateManager;method_12304(F)V"))
    private float s1mp1e$outlineWidth(float vanilla) {
        return BlockOutlineModule.active() == null ? vanilla : BlockOutlineModule.lineWidth(vanilla);
    }

    @WrapOperation(method = "drawBlockOutline",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/render/WorldRenderer;method_19164(Lnet/minecraft/util/shapes/VoxelShape;DDDFFFF)V"))
    private void s1mp1e$outline(VoxelShape shape, double dx, double dy, double dz, float r, float g, float b, float a,
                                Operation<Void> original) {
        if (BlockOutlineModule.active() == null) {
            original.call(shape, dx, dy, dz, r, g, b, a);
            return;
        }
        try {
            BlockOutlineModule.submitFill(shape, dx, dy, dz);
        } catch (Throwable ignored) {
        }
        // what drawHighlightedBlockOutline had set up for the lines (the fill leaves blend off / depth mask on)
        GlStateManager.enableBlend();
        GlStateManager.blendFuncSeparate(770, 771, 1, 0);
        GlStateManager.disableTexture();
        GlStateManager.depthMask(false);
        int argb = BlockOutlineModule.outlineColor(0);
        original.call(shape, dx, dy, dz, ((argb >> 16) & 255) / 255f, ((argb >> 8) & 255) / 255f, (argb & 255) / 255f,
                ((argb >>> 24) & 255) / 255f);
    }
}
