package dev.s1mp1e.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.s1mp1e.client.module.BlockOutlineModule;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feeds {@link BlockOutlineModule} into vanilla's block-selection outline.
 *
 * <p>26.2 path (verified with javap): {@code LevelRenderer.submitBlockOutline(PoseStack, SubmitNodeCollector,
 * LevelRenderState)} returns at once when {@code blockOutlineRenderState == null} (nothing targeted), translates the
 * pose to the block, then calls {@code submitHitOutline(pose, collector, renderType, state, int colour, float width,
 * boolean translucent)} — {@code ordinal = 0} is the high-contrast black halo (only when that accessibility option
 * is on; left untouched), {@code ordinal = 1} is the normal outline, which is what we restyle.
 *
 * <p>Fair play: all three handlers sit INSIDE {@code submitBlockOutline} after its null-target early return, so they
 * only ever restyle the block the player is already looking at.
 */
@Mixin(LevelRenderer.class)
public abstract class BlockOutlineMixin {

    private static final String HIT_OUTLINE =
            "Lnet/minecraft/client/renderer/LevelRenderer;submitHitOutline("
            + "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;"
            + "Lnet/minecraft/client/renderer/rendertype/RenderType;"
            + "Lnet/minecraft/client/renderer/state/level/BlockOutlineRenderState;IFZ)V";

    /** Colour (arg 4) of the normal outline. */
    @ModifyArg(method = "submitBlockOutline",
               at = @At(value = "INVOKE", target = HIT_OUTLINE, ordinal = 1), index = 4)
    private int s1mp1e$outlineColour(int vanilla) {
        return BlockOutlineModule.outlineColor(vanilla);
    }

    /** Line width (arg 5) of the normal outline — a per-vertex attribute of the lines pipeline, so it is honoured. */
    @ModifyArg(method = "submitBlockOutline",
               at = @At(value = "INVOKE", target = HIT_OUTLINE, ordinal = 1), index = 5)
    private float s1mp1e$outlineWidth(float vanilla) {
        return BlockOutlineModule.lineWidth(vanilla);
    }

    /** Optional translucent fill, submitted just before the outline with the same block-relative pose. */
    @Inject(method = "submitBlockOutline",
            at = @At(value = "INVOKE", target = HIT_OUTLINE, ordinal = 1))
    private void s1mp1e$outlineFill(PoseStack pose, SubmitNodeCollector collector, LevelRenderState levelState,
                                    CallbackInfo ci) {
        try {
            BlockOutlineModule.submitFill(pose, collector, levelState.blockOutlineRenderState);
        } catch (Throwable t) {
            dev.s1mp1e.client.ErrorOnce.report("BlockOutline fill", t);
        }
    }
}
