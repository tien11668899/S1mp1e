package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.module.BlockOutlineModule;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * Feeds {@link BlockOutlineModule} into vanilla's block-selection outline (H1).
 *
 * <p>1.21.1 path (verified with javap): {@code WorldRenderer.render} fetches
 * {@code bufferBuilders.getEntityVertexConsumers().getBuffer(RenderLayer.getLines())} and calls
 * {@code drawBlockOutline(MatrixStack, VertexConsumer, Entity, double cameraX, double cameraY,
 * double cameraZ, BlockPos, BlockState)}, which draws {@code drawCuboidShapeOutline(matrices, vc, shape,
 * pos.x - cameraX, pos.y - cameraY, pos.z - cameraZ, r, g, b, a)} (float colour, args 6..9). NOTE the
 * three doubles are the CAMERA position, not an offset. It only runs while the player is looking at a
 * block, so all hooks below only ever restyle the block already targeted (fair play).
 *
 * <ul>
 *   <li><b>Colour</b> — {@code @ModifyArgs} rewrites r/g/b/a on the {@code drawCuboidShapeOutline}
 *       INVOKE from the module colour (or its chroma).</li>
 *   <li><b>Width</b> — core profile expands every LINES segment to a quad in the {@code rendertype_lines}
 *       VERTEX shader using the {@code LineWidth} uniform (= {@code RenderSystem.lineWidth}), so there is
 *       no GPU {@code glLineWidth} clamp. The outline vertices sit in the ENTITY immediate's lines layer
 *       (the builder vanilla fetched just before the call), so at TAIL that layer is flushed under
 *       {@link BlockOutlineModule#widthOverride}, which {@code RenderPhaseLineWidthMixin} turns into the
 *       module's width for this one draw only. (It cannot be pre-flushed at HEAD: vanilla already holds that
 *       builder, and ending it there crashes with "Not building!". Only lines queued in the same layer
 *       before the outline — debug hitboxes with F3+B — share the flush.)</li>
 *   <li><b>Fill</b> — at TAIL, a translucent depth-tested ({@code getDebugFilledBox}, LEQUAL) fill of
 *       the targeted block's own outline shape, drawn immediately (never through walls).</li>
 * </ul>
 */
@Mixin(WorldRenderer.class)
public abstract class BlockOutlineMixin {

    private static final String CUBOID =
            "Lnet/minecraft/client/render/WorldRenderer;drawCuboidShapeOutline("
            + "Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumer;"
            + "Lnet/minecraft/util/shape/VoxelShape;DDDFFFF)V";

    /** Colour (args 6..9 = r,g,b,a) of the block outline. */
    @ModifyArgs(method = "drawBlockOutline", at = @At(value = "INVOKE", target = CUBOID))
    private void s1mp1e$outlineColour(Args args) {
        BlockOutlineModule m = BlockOutlineModule.active();
        if (m == null) return;
        int argb = BlockOutlineModule.outlineColor(0);
        args.set(6, ((argb >> 16) & 255) / 255f);
        args.set(7, ((argb >> 8)  & 255) / 255f);
        args.set(8, (argb & 255) / 255f);
        args.set(9, ((argb >>> 24) & 255) / 255f);
    }

    /** Flush the outline's lines NOW under the width override, then add the optional fill. */
    @Inject(method = "drawBlockOutline", at = @At("TAIL"))
    private void s1mp1e$outlineWidthAndFill(MatrixStack matrices, VertexConsumer vc, Entity entity,
                                            double camX, double camY, double camZ, BlockPos pos, BlockState state,
                                            CallbackInfo ci) {
        if (BlockOutlineModule.active() == null) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        VertexConsumerProvider.Immediate imm = mc.getBufferBuilders().getEntityVertexConsumers();
        try {
            BlockOutlineModule.widthOverride = true;
            imm.draw(RenderLayer.getLines());
        } catch (Throwable ignored) {
        } finally {
            BlockOutlineModule.widthOverride = false;
        }
        try {
            if (mc.world == null) return;
            VoxelShape shape = state.getOutlineShape(mc.world, pos, ShapeContext.of(entity));
            BlockOutlineModule.submitFill(matrices, imm, shape,
                    pos.getX() - camX, pos.getY() - camY, pos.getZ() - camZ);
            imm.draw(RenderLayer.getDebugFilledBox());
        } catch (Throwable ignored) {
            // A fill failure must never break the world render; the outline still recolours.
        }
    }
}
