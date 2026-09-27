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
 * <p>1.18.2 path (verified by decompile): {@code WorldRenderer.drawBlockOutline(MatrixStack,
 * VertexConsumer, Entity, double cameraX, double cameraY, double cameraZ, BlockPos, BlockState)} draws
 * the outline with a SINGLE static call {@code drawShapeOutline(matrices, vc, shape, pos-cameraX/Y/Z,
 * 0f, 0f, 0f, 0.4f)} — float colour in args 6..9. The offset args here are the CAMERA position (the
 * method subtracts them from the block position inline), so the fill recomputes {@code dx = pos -
 * camera}. drawBlockOutline only runs while the player is looking at a block, so every hook below only
 * ever restyles the block already targeted (fair play).
 *
 * <ul>
 *   <li><b>Colour</b> — {@code @ModifyArgs} rewrites r/g/b/a on the {@code drawShapeOutline} INVOKE from
 *       the module colour (or its chroma).</li>
 *   <li><b>Fill</b> — {@code @Inject} at HEAD adds the optional depth-tested translucent fill of the
 *       targeted block's own outline shape (never through walls).</li>
 *   <li><b>Width</b> — {@code @Inject} at TAIL flushes the outline's lines layer under a width override
 *       so {@code RenderPhaseLineWidthMixin} forces the module's line width for this one draw only.</li>
 * </ul>
 */
@Mixin(WorldRenderer.class)
public abstract class BlockOutlineMixin {

    private static final String SHAPE_OUTLINE =
            "Lnet/minecraft/client/render/WorldRenderer;drawShapeOutline("
            + "Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumer;"
            + "Lnet/minecraft/util/shape/VoxelShape;DDDFFFF)V";

    /** Colour (args 6..9 = r,g,b,a) of the block outline. */
    @ModifyArgs(method = "drawBlockOutline", at = @At(value = "INVOKE", target = SHAPE_OUTLINE))
    private void s1mp1e$outlineColour(Args args) {
        BlockOutlineModule m = BlockOutlineModule.active();
        if (m == null) return;
        int argb = BlockOutlineModule.outlineColor(0);
        args.set(6, ((argb >> 16) & 255) / 255f);
        args.set(7, ((argb >> 8)  & 255) / 255f);
        args.set(8, (argb & 255) / 255f);
        args.set(9, ((argb >>> 24) & 255) / 255f);
    }

    /** Optional translucent depth-tested fill of the targeted block, added before the outline draws. */
    @Inject(method = "drawBlockOutline", at = @At("HEAD"))
    private void s1mp1e$outlineFill(MatrixStack matrices, VertexConsumer vc, Entity entity,
                                    double cameraX, double cameraY, double cameraZ, BlockPos pos, BlockState state,
                                    CallbackInfo ci) {
        BlockOutlineModule m = BlockOutlineModule.active();
        if (m == null) return;
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.world == null) return;
            VoxelShape shape = state.getOutlineShape(mc.world, pos, ShapeContext.of(entity));
            double dx = (double) pos.getX() - cameraX;
            double dy = (double) pos.getY() - cameraY;
            double dz = (double) pos.getZ() - cameraZ;
            BlockOutlineModule.submitFill(matrices, shape, dx, dy, dz);
        } catch (Throwable ignored) {
            // A fill failure must never break the world render; the outline still recolours.
        }
    }

    /** Flush the outline's lines layer NOW, under a width override, so the module line width applies to
     *  this draw only (other line layers flush later at their vanilla width).
     *
     *  <p>The outline is buffered into the ENTITY vertex consumers — {@code WorldRenderer.render} draws it
     *  with {@code this.bufferBuilders.getEntityVertexConsumers().getBuffer(RenderLayer.getLines())} — so
     *  we early-flush THAT provider's {@code getLines()} buffer here (an empty later flush is a no-op).
     *  {@code RenderPhaseLineWidthMixin} forces the module width for exactly this one flush because
     *  {@link BlockOutlineModule#widthOverride} is set only around it. Core-profile GL does not clamp it:
     *  the LINES render type expands to quads in the {@code rendertype_lines} vertex shader. */
    @Inject(method = "drawBlockOutline", at = @At("TAIL"))
    private void s1mp1e$outlineWidth(MatrixStack matrices, VertexConsumer vc, Entity entity,
                                     double cameraX, double cameraY, double cameraZ, BlockPos pos, BlockState state,
                                     CallbackInfo ci) {
        if (BlockOutlineModule.active() == null) return;
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            VertexConsumerProvider.Immediate imm = mc.getBufferBuilders().getEntityVertexConsumers();
            BlockOutlineModule.widthOverride = true;
            imm.draw(RenderLayer.getLines());
        } catch (Throwable ignored) {
        } finally {
            BlockOutlineModule.widthOverride = false;
        }
    }
}
