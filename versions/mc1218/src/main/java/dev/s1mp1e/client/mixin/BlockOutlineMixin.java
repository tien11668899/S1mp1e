package dev.s1mp1e.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.s1mp1e.client.module.BlockOutlineModule;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The BlockOutline module on 1.21.6-1.21.8's {@code LevelRenderer.renderBlockOutline}: it takes the {@code lines} buffer
 * (getBuffer ordinal 1; ordinal 0 is the high-contrast secondary outline), draws the outline with
 * {@code renderHitOutline(..., int colour)} (ordinal 1) and draws that batch with {@code endLastBatch}.
 * <ul>
 *   <li>colour: the last argument of the main {@code renderHitOutline};</li>
 *   <li>fill: drawn right before vanilla takes its {@code lines} buffer (see {@link BlockOutlineModule#renderFill});</li>
 *   <li>width: 1.21.8 has no width argument; the {@code lines} render type sets {@code RenderSystem.lineWidth} while the
 *       batch draws, so the {@code endLastBatch} is bracketed and {@code OutlineLineWidthMixin} widens only inside it.</li>
 * </ul>
 * 26.2's {@code submitBlockOutline} passes colour and width to {@code submitHitOutline} instead.
 */
@Mixin(LevelRenderer.class)
public abstract class BlockOutlineMixin {
    private static final String HIT_OUTLINE =
            "Lnet/minecraft/client/renderer/LevelRenderer;renderHitOutline("
            + "Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;"
            + "Lnet/minecraft/world/entity/Entity;DDDLnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/world/level/block/state/BlockState;I)V";
    private static final String GET_BUFFER =
            "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;getBuffer("
            + "Lnet/minecraft/client/renderer/RenderType;)Lcom/mojang/blaze3d/vertex/VertexConsumer;";
    private static final String END_LAST_BATCH = "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;endLastBatch()V";

    @ModifyArg(method = "renderBlockOutline",
               at = @At(value = "INVOKE", target = HIT_OUTLINE, ordinal = 1), index = 8)
    private int s1mp1e$outlineColour(int vanilla) {
        return BlockOutlineModule.outlineColor(vanilla);
    }

    @Inject(method = "renderBlockOutline", at = @At(value = "INVOKE", target = GET_BUFFER, ordinal = 1))
    private void s1mp1e$outlineFill(Camera camera, MultiBufferSource.BufferSource buffers, PoseStack pose,
                                    boolean translucent, CallbackInfo ci) {
        try {
            BlockOutlineModule.renderFill(pose, buffers, camera);
        } catch (Throwable t) {
            dev.s1mp1e.client.ErrorOnce.report("BlockOutline fill", t);
        }
    }

    @Inject(method = "renderBlockOutline", at = @At(value = "INVOKE", target = END_LAST_BATCH))
    private void s1mp1e$widthOn(Camera camera, MultiBufferSource.BufferSource buffers, PoseStack pose,
                                boolean translucent, CallbackInfo ci) {
        BlockOutlineModule.outlineWidthScope = true;
    }

    @Inject(method = "renderBlockOutline", at = @At(value = "INVOKE", target = END_LAST_BATCH, shift = At.Shift.AFTER))
    private void s1mp1e$widthOff(Camera camera, MultiBufferSource.BufferSource buffers, PoseStack pose,
                                 boolean translucent, CallbackInfo ci) {
        BlockOutlineModule.outlineWidthScope = false;
    }
}
