package dev.s1mp1e.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.s1mp1e.client.module.BlockOutlineModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.state.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The BlockOutline module on 1.21.9+'s
 * {@code LevelRenderer.renderBlockOutline(buffers, pose, translucent, levelState)}:
 * it takes the {@code lines} buffer (getBuffer ordinal 1; ordinal 0 is the high-contrast secondary outline), draws the
 * outline with {@code renderHitOutline(..., int colour)} (ordinal 1) and draws that batch with {@code endLastBatch}.
 * <ul>
 *   <li>colour: the last argument of the main {@code renderHitOutline};</li>
 *   <li>fill: drawn right before vanilla takes its {@code lines} buffer (see {@link BlockOutlineModule#renderFill});</li>
 *   <li>width: 1.21.x has no width argument; the {@code lines} render type sets {@code RenderSystem.lineWidth} while the
 *       batch draws, so the {@code endLastBatch} is bracketed and {@code OutlineLineWidthMixin} widens only inside it.</li>
 * </ul>
 * 1.21.9 draws from the {@code BlockOutlineRenderState} (colour = 7th {@code renderHitOutline} argument) and no longer
 * passes the camera (the fill takes the main camera); 1.21.6-1.21.8 read the hit result and the camera directly (colour
 * = 9th argument). 26.2's {@code submitBlockOutline} passes colour and width to {@code submitHitOutline} instead.
 */
@Mixin(LevelRenderer.class)
public abstract class BlockOutlineMixin {
    private static final String HIT_OUTLINE =
            "Lnet/minecraft/client/renderer/LevelRenderer;renderHitOutline("
            + "Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;"
            + "DDDLnet/minecraft/client/renderer/state/BlockOutlineRenderState;I)V";
    private static final String GET_BUFFER =
            "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;getBuffer("
            + "Lnet/minecraft/client/renderer/RenderType;)Lcom/mojang/blaze3d/vertex/VertexConsumer;";
    private static final String END_LAST_BATCH = "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;endLastBatch()V";

    @ModifyArg(method = "renderBlockOutline",
               at = @At(value = "INVOKE", target = HIT_OUTLINE, ordinal = 1), index = 6)
    private int s1mp1e$outlineColour(int vanilla) {
        return BlockOutlineModule.outlineColor(vanilla);
    }

    @Inject(method = "renderBlockOutline", at = @At(value = "INVOKE", target = GET_BUFFER, ordinal = 1))
    private void s1mp1e$outlineFill(MultiBufferSource.BufferSource buffers, PoseStack pose, boolean translucent,
                                    LevelRenderState state, CallbackInfo ci) {
        try {
            BlockOutlineModule.renderFill(pose, buffers, Minecraft.getInstance().gameRenderer.getMainCamera());
        } catch (Throwable t) {
            dev.s1mp1e.client.ErrorOnce.report("BlockOutline fill", t);
        }
    }

    @Inject(method = "renderBlockOutline", at = @At(value = "INVOKE", target = END_LAST_BATCH))
    private void s1mp1e$widthOn(MultiBufferSource.BufferSource buffers, PoseStack pose, boolean translucent,
                                LevelRenderState state, CallbackInfo ci) {
        BlockOutlineModule.outlineWidthScope = true;
    }

    @Inject(method = "renderBlockOutline", at = @At(value = "INVOKE", target = END_LAST_BATCH, shift = At.Shift.AFTER))
    private void s1mp1e$widthOff(MultiBufferSource.BufferSource buffers, PoseStack pose, boolean translucent,
                                 LevelRenderState state, CallbackInfo ci) {
        BlockOutlineModule.outlineWidthScope = false;
    }
}
