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
 * The BlockOutline module on 1.21.11's
 * {@code LevelRenderer.renderBlockOutline(buffers, pose, translucent, levelState)}:
 * it takes the {@code lines} buffer (getBuffer ordinal 1; ordinal 0 is the high-contrast secondary outline) and draws the
 * outline with {@code renderHitOutline(pose, lines, x, y, z, outlineState, int colour, float width)} (ordinal 1).
 * <ul>
 *   <li>colour: the 7th argument of the main {@code renderHitOutline};</li>
 *   <li>width: its 8th — 1.21.11 passes the line width per draw, as 26.x does (1.21.6-1.21.10 set
 *       {@code RenderSystem.lineWidth} while the batch drew, bracketed by an {@code OutlineLineWidthMixin});</li>
 *   <li>fill: drawn right before vanilla takes its {@code lines} buffer (see {@link BlockOutlineModule#renderFill});
 *       the camera is the main camera (the method gets none).</li>
 * </ul>
 * 26.2's {@code submitBlockOutline} passes colour and width to {@code submitHitOutline} instead.
 */
@Mixin(LevelRenderer.class)
public abstract class BlockOutlineMixin {
    private static final String HIT_OUTLINE =
            "Lnet/minecraft/client/renderer/LevelRenderer;renderHitOutline("
            + "Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;"
            + "DDDLnet/minecraft/client/renderer/state/BlockOutlineRenderState;IF)V";
    private static final String GET_BUFFER =
            "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;getBuffer("
            + "Lnet/minecraft/client/renderer/RenderType;)Lcom/mojang/blaze3d/vertex/VertexConsumer;";

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

    /** Line width (8th argument) of the normal outline: 1.21.11 passes it per draw, as 26.x does. */
    @ModifyArg(method = "renderBlockOutline",
               at = @At(value = "INVOKE", target = HIT_OUTLINE, ordinal = 1), index = 7)
    private float s1mp1e$outlineWidth(float vanilla) {
        return BlockOutlineModule.lineWidth(vanilla);
    }
}
