package dev.s1mp1e.glass.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.s1mp1e.client.module.BlockOutlineModule;
import net.minecraft.client.render.RenderPhase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Lets {@link BlockOutlineModule} force the block-outline line width (H1) without touching any other
 * line layer.
 *
 * <p>The vanilla LINES layer's {@code LineWidth} phase (empty {@code OptionalDouble}) computes and
 * sets {@code RenderSystem.lineWidth(max(2.5, framebufferWidth/1920*2.5))} on setup. This redirect
 * substitutes the module's width, but ONLY while {@link BlockOutlineModule#widthOverride} is set —
 * which {@code BlockOutlineMixin} does solely around its own flush of the outline's lines layer (the
 * ENTITY immediate). Every other line layer (leash, fishing line, F3+B hitboxes) flushes with the
 * override clear and so keeps vanilla's width exactly.
 *
 * <p>Core profile (1.17+): {@code RenderSystem.lineWidth} feeds the {@code LineWidth} uniform of the
 * {@code rendertype_lines} VERTEX shader, which expands each segment into a screen-space quad of that
 * many pixels — so this is NOT subject to the GPU's {@code glLineWidth} clamp; width 1..8 is exact.
 *
 * <p>Targets the synthetic LineWidth begin action {@code method_23554} (javap-verified in the 1.21.1
 * named jar; the intermediary name is the same in production). {@code require = 1} so the audit proves
 * it applied.
 */
@Mixin(RenderPhase.LineWidth.class)
public abstract class RenderPhaseLineWidthMixin {

    @Redirect(
        method = "method_23554",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;lineWidth(F)V"),
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
