package dev.s1mp1e.glass.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.s1mp1e.client.module.BlockOutlineModule;
import net.minecraft.client.render.RenderPhase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Lets {@link BlockOutlineModule} force the block-outline line width (H1) without touching any other line
 * layer.
 *
 * <p>The vanilla LINES layer's {@code LineWidth} phase (empty {@code OptionalDouble}) computes and sets
 * {@code RenderSystem.lineWidth(max(2.5, framebufferWidth/1920*2.5))} on setup. This redirect substitutes
 * the module's width, but ONLY while {@link BlockOutlineModule#widthOverride} is set — which
 * {@code BlockOutlineMixin} does solely around its own early flush of the outline's lines layer. Every
 * other line layer (leash, fishing line, F3+B hitboxes) flushes with the override clear and so keeps
 * vanilla's width exactly.
 *
 * <p>1.17.1 path (verified by decompile): {@code RenderPhase$LineWidth}'s begin-action lambda
 * {@code method_23554(OptionalDouble)} contains TWO {@code RenderSystem.lineWidth(F)} calls: ordinal 0 is
 * the {@code width.isPresent()} branch, ordinal 1 is the {@code else} branch that computes
 * {@code max(2.5, framebufferWidth/1920*2.5)} — the one the vanilla LINES layer (built with
 * {@code OptionalDouble.empty()}) actually runs. We pin {@code ordinal = 1}, {@code require = 1} so a
 * mapping drift is caught by the audit. Core-profile GL does NOT clamp this: the LINES render type
 * expands to screen-space quads in the {@code rendertype_lines} vertex shader using the line width, so
 * 1..8 px visibly thickens the outline (verified with DevShot shots at width 1 vs width 8).
 *
 * <p>On 1.16.5 {@code RenderPhase.LineWidth} is {@code public} in the yarn compile classpath, so it is targeted by
 * {@code value = RenderPhase.LineWidth.class} directly (the 1.17.1 sibling has it {@code protected} and uses a string
 * target instead). {@code method_23554} is the intermediary name of the begin-action lambda, stable across both.
 */
@Mixin(RenderPhase.LineWidth.class)
public abstract class RenderPhaseLineWidthMixin {

    @Redirect(
        method = "method_23554",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;lineWidth(F)V", ordinal = 1),
        require = 1
    )
    private static void s1mp1e$outlineWidth(float vanilla) {
        if (BlockOutlineModule.widthOverride) {
            RenderSystem.lineWidth(BlockOutlineModule.lineWidth(vanilla));
        } else {
            RenderSystem.lineWidth(vanilla);
        }
    }
}
