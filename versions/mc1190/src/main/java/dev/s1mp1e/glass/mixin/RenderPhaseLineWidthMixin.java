package dev.s1mp1e.glass.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.s1mp1e.client.module.BlockOutlineModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Line-width control for the Block Outline module (H1) on the 1.19.2 core profile.
 *
 * <p>The block outline is drawn into {@code RenderLayer.getLines()}, whose {@code LineWidth} phase has an EMPTY width
 * and therefore calls {@code RenderSystem.lineWidth(max(2.5, framebufferWidth/1920 * 2.5))} when the buffer is flushed
 * (the width is decided by the layer at flush time, NOT read from the current GL state - so a plain
 * {@code RenderSystem.lineWidth} set before the draw would be overwritten). {@code method_23554} is that phase's begin
 * action. This redirect replaces the {@code RenderSystem.lineWidth} call so that, ONLY while the block outline is being
 * drawn and flushed ({@link BlockOutlineMixin#s1mp1e$outlineDrawing}) and the module is active, the width becomes the
 * module's setting (scaled by resolution the same way vanilla scales). Every other line layer, and every frame where
 * no block is targeted, is untouched.
 *
 * <p>{@code require = 0}: if a future yarn build renames the lambda, the width simply stays vanilla instead of
 * crashing - a graceful degradation for one setting on an off-by-default module.
 */
// RenderPhase.LineWidth is protected in the yarn mappings, so it cannot be referenced as a class literal in `value`;
// the string target is required (this triggers one benign Mixin-AP note about value-vs-targets, not a javac warning).
@Mixin(targets = "net.minecraft.client.render.RenderPhase$LineWidth")
public abstract class RenderPhaseLineWidthMixin {

    @Redirect(method = "method_23554",
              at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;lineWidth(F)V"),
              require = 0)
    private static void s1mp1e$outlineWidth(float vanilla) {
        if (BlockOutlineModule.outlineDrawing && BlockOutlineModule.active() != null) {
            RenderSystem.lineWidth(BlockOutlineModule.lineWidth(vanilla));
        } else {
            RenderSystem.lineWidth(vanilla);
        }
    }
}
