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
 * <p>1.20.1 path (verified by decompile): {@code WorldRenderer.drawBlockOutline(MatrixStack,
 * VertexConsumer, Entity, double cameraX, double cameraY, double cameraZ, BlockPos, BlockState)} draws
 * the outline with a SINGLE {@code drawCuboidShapeOutline(matrices, vc, shape, pos-cameraX/Y/Z, r, g, b,
 * a)} call (float colour, args 6..9). Note the offset args here are the CAMERA position (the method
 * subtracts them from the block position inline), unlike 1.21.1 where they were already block-relative;
 * the fill below therefore recomputes {@code dx = pos - camera}. The method only runs while the player
 * is looking at a block, so all three hooks below only ever restyle the block already targeted (fair
 * play).
 *
 * <ul>
 *   <li><b>Colour</b> — {@code @ModifyArgs} rewrites r/g/b/a on the {@code drawCuboidShapeOutline}
 *       INVOKE from the module colour (or its chroma).</li>
 *   <li><b>Fill</b> — {@code @Inject} at HEAD adds the optional depth-tested translucent fill of the
 *       targeted block's own outline shape (never through walls).</li>
 *   <li><b>Width</b> — {@code @Inject} at TAIL flushes the lines layer under a width override so
 *       {@code RenderPhaseLineWidthMixin} forces the module's line width for this one draw only.</li>
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
            BlockOutlineModule.submitFill(matrices, mc.getBufferBuilders().getEffectVertexConsumers(),
                                          shape, dx, dy, dz);
        } catch (Throwable ignored) {
            // A fill failure must never break the world render; the outline still recolours.
        }
    }

    /** Flush the outline's lines layer NOW, under a width override, so the module line width applies
     *  to this draw only (other line layers flush later at their vanilla width).
     *
     *  <p>The outline is buffered into the ENTITY vertex consumers — {@code WorldRenderer.render} draws
     *  it with {@code this.bufferBuilders.getEntityVertexConsumers().getBuffer(RenderLayer.getLines())}
     *  — so we early-flush THAT provider's {@code getLines()} buffer here (the earlier port flushed the
     *  effect consumers, an empty buffer, which is why width never changed). In 1.20.1 the block outline
     *  is effectively the only thing writing {@code getLines()} in the entity pass (leashes use
     *  {@code getLeash()}), so this flushes just the outline; the later vanilla {@code immediate.draw()}
     *  then flushes an empty lines buffer. {@code RenderPhaseLineWidthMixin} forces the module width for
     *  exactly this one flush because {@link BlockOutlineModule#widthOverride} is set only around it. */
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
