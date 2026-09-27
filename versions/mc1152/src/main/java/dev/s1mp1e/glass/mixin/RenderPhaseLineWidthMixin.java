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
 * <p>The vanilla LINES layer's {@code LineWidth} phase (built with an empty {@code OptionalDouble}) runs its
 * begin lambda {@code method_23554(OptionalDouble)}, whose {@code else} branch computes and sets
 * {@code RenderSystem.lineWidth(max(2.5, framebufferWidth/1920*2.5))} (javap-verified: two {@code lineWidth}
 * calls — ordinal 0 the {@code width.isPresent()} branch, ordinal 1 the {@code max(2.5, ...)} else branch the
 * empty LINES layer runs). This redirect substitutes the module's width, but ONLY while
 * {@link BlockOutlineModule#widthOverride} is set — which {@code BlockOutlineMixin} does solely around its own
 * early flush of the outline's lines layer. Every other line layer (leash, fishing line, F3+B hitboxes) flushes
 * with the override clear and so keeps vanilla's width exactly.
 *
 * <p>1.15.2 runs the legacy (compatibility) GL profile, so {@code glLineWidth} draws real thick GL lines — 1..8 px
 * visibly thickens the outline (verified with DevShot shots at width 1 vs width 8). {@code RenderPhase.LineWidth}
 * is {@code public} in the 1.15.2 yarn compile classpath, so it is targeted by {@code RenderPhase.LineWidth.class}
 * directly. {@code method_23554} is the intermediary name of the begin-action lambda. {@code require = 1} makes a
 * mapping drift fail the audit rather than silently no-op.
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
