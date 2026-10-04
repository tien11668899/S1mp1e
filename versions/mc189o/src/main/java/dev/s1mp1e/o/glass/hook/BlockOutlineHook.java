package dev.s1mp1e.o.glass.hook;

import dev.s1mp1e.o.client.module.BlockOutlineModule;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.render.platform.GlStateManager;
import net.minecraft.client.render.world.WorldRenderer;
import net.minecraft.client.render.vertex.Tesselator;
import net.minecraft.client.render.vertex.BufferBuilder;
import net.minecraft.client.render.vertex.DefaultVertexFormat;
import net.minecraft.entity.living.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.HitResult;
import net.minecraft.world.World;
import dev.s1mp1e.o.event.DrawBlockHighlightEvent;
import dev.s1mp1e.o.event.EventPriority;
import dev.s1mp1e.o.event.SubscribeEvent;
import org.lwjgl.opengl.GL11;

/**
 * Feature H1 — Block Outline. Recolours / re-widths the vanilla block-selection outline and, optionally, adds a
 * depth-tested translucent fill of the targeted block. Off by default; only active while
 * {@link BlockOutlineModule#active()} returns the module.
 *
 * <p>Driven from Forge's {@code DrawBlockHighlightEvent}, which fires only when the player is looking at a block
 * (right before {@code WorldRenderer.renderBlockOutline}). When the module is on, this cancels the vanilla draw and
 * redraws the SAME box with the module's colour + line width, then the optional fill — so it never adds an
 * outline, never targets a second block, never disables the depth test and never shows through walls (the fill is
 * drawn with depth-test left on, exactly like vanilla's outline). Pure re-skin, fair-play.
 */
public final class BlockOutlineHook {

    /** Vanilla's outline colour: black at alpha 0.4 (= 102). */
    private static final int VANILLA_OUTLINE = 0x66000000;
    /** Vanilla's base line width. */
    private static final float VANILLA_WIDTH = 2.0f;

    @SubscribeEvent(priority = EventPriority.NORMAL)
    public void onDrawHighlight(DrawBlockHighlightEvent e) {
        BlockOutlineModule m = BlockOutlineModule.active();
        if (m == null) return;                       // module off -> vanilla draws as normal
        if (e.subID != 0) return;                    // main selection pass only (execute == 0)
        HitResult mop = e.target;
        if (mop == null || mop.type != HitResult.Type.BLOCK) return;

        PlayerEntity player = e.player;
        if (player == null) return;
        World world = player.world;
        if (world == null) return;
        BlockPos pos = mop.getPos();
        if (pos == null) return;
        Block block = world.getBlockState(pos).getBlock();
        if (block.getMaterial() == Material.AIR || !world.getWorldBorder().contains(pos)) return;

        // We are taking over the draw for this frame.
        e.setCanceled(true);

        try {
            float pt = e.partialTicks;
            double d0 = player.prevX + (player.x - player.prevX) * (double) pt;
            double d1 = player.prevY + (player.y - player.prevY) * (double) pt;
            double d2 = player.prevZ + (player.z - player.prevZ) * (double) pt;

            block.updateShape(world, pos);
            Box base = block.getOutlineShape(world, pos);

            GlStateManager.enableBlend();
            GlStateManager.blendFuncSeparate(770, 771, 1, 0);
            GlStateManager.disableTexture();
            GlStateManager.depthMask(false);   // like vanilla: don't write depth (depth TEST stays on -> no x-ray)

            // ---- outline (recoloured + re-widthed) ----
            int argb = BlockOutlineModule.outlineColor(VANILLA_OUTLINE);
            float w = BlockOutlineModule.lineWidth(VANILLA_WIDTH);
            GL11.glLineWidth(Math.max(1f, w));
            Box outline = base.grown(BlockOutlineModule.FILL_INFLATE,
                    BlockOutlineModule.FILL_INFLATE, BlockOutlineModule.FILL_INFLATE).moved(-d0, -d1, -d2);
            WorldRenderer.renderOutlineShape(outline,
                    (argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, (argb >>> 24) & 0xFF);

            // ---- optional translucent fill of the targeted block ----
            int fill = m.fillColor();
            if (fill != 0) {
                Box box = base.grown(BlockOutlineModule.FILL_INFLATE,
                        BlockOutlineModule.FILL_INFLATE, BlockOutlineModule.FILL_INFLATE).moved(-d0, -d1, -d2);
                drawFilledBox(box, fill);
            }

            GlStateManager.depthMask(true);
            GlStateManager.enableTexture();
            GlStateManager.disableBlend();
            GlStateManager.color4f(1f, 1f, 1f, 1f);
            GL11.glLineWidth(1f);
        } catch (Throwable t) {
            // never take the world render down; leave GL in a sane state
            try {
                GlStateManager.depthMask(true);
                GlStateManager.enableTexture();
                GlStateManager.disableBlend();
                GlStateManager.color4f(1f, 1f, 1f, 1f);
            } catch (Throwable ignored) {}
        }
    }

    /** Six {@code POSITION_COLOR} quads of a translucent axis-aligned box (depth test as left by the caller). */
    private static void drawFilledBox(Box b, int argb) {
        int a = (argb >>> 24) & 0xFF, r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, bl = argb & 0xFF;
        Tesselator tess = Tesselator.getInstance();
        BufferBuilder wr = tess.getBuffer();
        wr.begin(7, DefaultVertexFormat.POSITION_COLOR);
        // bottom (minY) / top (maxY)
        wr.vertex(b.minX, b.minY, b.minZ).color(r, g, bl, a).nextVertex();
        wr.vertex(b.maxX, b.minY, b.minZ).color(r, g, bl, a).nextVertex();
        wr.vertex(b.maxX, b.minY, b.maxZ).color(r, g, bl, a).nextVertex();
        wr.vertex(b.minX, b.minY, b.maxZ).color(r, g, bl, a).nextVertex();

        wr.vertex(b.minX, b.maxY, b.maxZ).color(r, g, bl, a).nextVertex();
        wr.vertex(b.maxX, b.maxY, b.maxZ).color(r, g, bl, a).nextVertex();
        wr.vertex(b.maxX, b.maxY, b.minZ).color(r, g, bl, a).nextVertex();
        wr.vertex(b.minX, b.maxY, b.minZ).color(r, g, bl, a).nextVertex();
        // north (minZ) / south (maxZ)
        wr.vertex(b.minX, b.minY, b.minZ).color(r, g, bl, a).nextVertex();
        wr.vertex(b.minX, b.maxY, b.minZ).color(r, g, bl, a).nextVertex();
        wr.vertex(b.maxX, b.maxY, b.minZ).color(r, g, bl, a).nextVertex();
        wr.vertex(b.maxX, b.minY, b.minZ).color(r, g, bl, a).nextVertex();

        wr.vertex(b.maxX, b.minY, b.maxZ).color(r, g, bl, a).nextVertex();
        wr.vertex(b.maxX, b.maxY, b.maxZ).color(r, g, bl, a).nextVertex();
        wr.vertex(b.minX, b.maxY, b.maxZ).color(r, g, bl, a).nextVertex();
        wr.vertex(b.minX, b.minY, b.maxZ).color(r, g, bl, a).nextVertex();
        // west (minX) / east (maxX)
        wr.vertex(b.minX, b.minY, b.maxZ).color(r, g, bl, a).nextVertex();
        wr.vertex(b.minX, b.maxY, b.maxZ).color(r, g, bl, a).nextVertex();
        wr.vertex(b.minX, b.maxY, b.minZ).color(r, g, bl, a).nextVertex();
        wr.vertex(b.minX, b.minY, b.minZ).color(r, g, bl, a).nextVertex();

        wr.vertex(b.maxX, b.minY, b.minZ).color(r, g, bl, a).nextVertex();
        wr.vertex(b.maxX, b.maxY, b.minZ).color(r, g, bl, a).nextVertex();
        wr.vertex(b.maxX, b.maxY, b.maxZ).color(r, g, bl, a).nextVertex();
        wr.vertex(b.maxX, b.minY, b.maxZ).color(r, g, bl, a).nextVertex();
        tess.end();
    }
}
