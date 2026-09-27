package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.module.BlockOutlineModule;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * Feeds {@link BlockOutlineModule} into vanilla's block-selection outline.
 *
 * <p>1.19.2 path: {@code WorldRenderer.drawBlockOutline(matrices, vc, entity, camX, camY, camZ, pos, state)} draws the
 * outline via {@code drawShapeOutline(matrices, vc, shape, dx, dy, dz, red, green, blue, alpha)} into the deferred
 * {@code RenderLayer.getLines()} buffer. We:
 * <ul>
 *   <li>recolour the outline by modifying {@code drawShapeOutline}'s red/green/blue/alpha args
 *       ({@code @ModifyArgs}), so the module colour / chroma / custom alpha replace vanilla's black-at-0.4;</li>
 *   <li>add the optional translucent depth-tested fill of the SAME shape (block-relative matrix), just before the
 *       outline is submitted;</li>
 *   <li>control the line width: 1.19.2's LINES layer sets its own resolution-scaled width when the buffer is flushed
 *       (not from the current GL state), so a flag marks the block-outline draw+flush window and
 *       {@link RenderPhaseLineWidthMixin} overrides the width during it. The flag is cleared at {@code render} TAIL.</li>
 * </ul>
 *
 * <p>Fair play: all of this sits inside {@code drawBlockOutline}, reached only for the block the player is already
 * looking at; the fill is depth-tested (no through-wall) and depth-mask-off (no occlusion). Nothing new is shown.
 */
@Mixin(WorldRenderer.class)
public abstract class BlockOutlineMixin {

    @Shadow private ClientWorld world;

    // drawBlockOutline calls the private drawCuboidShapeOutline(matrices, vc, shape, dx,dy,dz, r,g,b,a) directly
    // (drawShapeOutline is the public wrapper used elsewhere). Recolour its red(6)/green(7)/blue(8)/alpha(9) args.
    private static final String DRAW_CUBOID_SHAPE_OUTLINE =
            "Lnet/minecraft/client/render/WorldRenderer;drawCuboidShapeOutline("
            + "Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumer;"
            + "Lnet/minecraft/util/shape/VoxelShape;DDDFFFF)V";

    /** Recolour the outline: modify red(6)/green(7)/blue(8)/alpha(9) of drawShapeOutline from the module colour. */
    @ModifyArgs(method = "drawBlockOutline",
                at = @At(value = "INVOKE", target = DRAW_CUBOID_SHAPE_OUTLINE))
    private void s1mp1e$recolour(Args args) {
        if (BlockOutlineModule.active() == null) return;   // inactive -> leave vanilla args untouched
        int ar = Math.round(((Float) args.get(6)) * 255f) & 0xFF;
        int ag = Math.round(((Float) args.get(7)) * 255f) & 0xFF;
        int ab = Math.round(((Float) args.get(8)) * 255f) & 0xFF;
        int aa = Math.round(((Float) args.get(9)) * 255f) & 0xFF;
        int vanilla = (aa << 24) | (ar << 16) | (ag << 8) | ab;
        int c = BlockOutlineModule.outlineColor(vanilla);
        args.set(6, ((c >>> 16) & 0xFF) / 255f);
        args.set(7, ((c >>> 8) & 0xFF) / 255f);
        args.set(8, (c & 0xFF) / 255f);
        args.set(9, ((c >>> 24) & 0xFF) / 255f);
    }

    /** Mark the outline draw window (for width) and add the optional fill, at drawBlockOutline HEAD. */
    @Inject(method = "drawBlockOutline", at = @At("HEAD"))
    private void s1mp1e$outlineExtras(MatrixStack matrices, VertexConsumer vc, Entity entity,
                                      double camX, double camY, double camZ, BlockPos pos, BlockState state,
                                      CallbackInfo ci) {
        try {
            if (BlockOutlineModule.active() == null) return;
            BlockOutlineModule.outlineDrawing = true;
            VoxelShape shape = state.getOutlineShape(this.world, pos, ShapeContext.of(entity));
            if (shape != null && !shape.isEmpty()) {
                matrices.push();
                matrices.translate(pos.getX() - camX, pos.getY() - camY, pos.getZ() - camZ);
                BlockOutlineModule.renderFill(matrices, shape);
                matrices.pop();
            }
        } catch (Throwable ignored) { /* a fill/width failure must never break the world render */ }
    }

    /** Clear the outline-width window after the whole world frame (the LINES flush has run by now). */
    @Inject(method = "render", at = @At("TAIL"))
    private void s1mp1e$clearOutlineFlag(CallbackInfo ci) {
        BlockOutlineModule.outlineDrawing = false;
    }
}
