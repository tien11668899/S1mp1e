package dev.s1mp1e.glass.hook;

import dev.s1mp1e.client.module.BlockOutlineModule;
import net.minecraft.block.state.IBlockState;
import net.minecraft.block.material.Material;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.world.World;
import net.minecraftforge.client.event.DrawBlockHighlightEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Feature H1 — Block Outline. Recolours / re-widths the vanilla block-selection outline and,
 * optionally, adds a depth-tested translucent fill of the targeted block. Off by default; only
 * active while {@link BlockOutlineModule#active()} returns the module.
 *
 * <p>Driven from Forge's {@code DrawBlockHighlightEvent}, which fires only when the player is
 * looking at a block (right before {@code RenderGlobal.drawSelectionBox}). When the module is on,
 * this cancels the vanilla draw and redraws the SAME box with the module's colour + line width,
 * then the optional fill — so it never adds an outline, never targets a second block, never disables
 * the depth test and never shows through a wall (the fill is drawn with the depth test left on,
 * exactly like vanilla's outline). Pure re-skin, fair-play.
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
        if (e.getSubID() != 0) return;               // main selection pass only (execute == 0)
        RayTraceResult mop = e.getTarget();
        if (mop == null || mop.typeOfHit != RayTraceResult.Type.BLOCK) return;

        EntityPlayer player = e.getPlayer();
        if (player == null) return;
        World world = player.world;
        if (world == null) return;
        BlockPos pos = mop.getBlockPos();
        if (pos == null) return;
        IBlockState state = world.getBlockState(pos);
        if (state.getMaterial() == Material.AIR || !world.getWorldBorder().contains(pos)) return;

        // We are taking over the draw for this frame.
        e.setCanceled(true);

        try {
            float pt = e.getPartialTicks();
            double d0 = player.lastTickPosX + (player.posX - player.lastTickPosX) * (double) pt;
            double d1 = player.lastTickPosY + (player.posY - player.lastTickPosY) * (double) pt;
            double d2 = player.lastTickPosZ + (player.posZ - player.lastTickPosZ) * (double) pt;

            AxisAlignedBB base = state.getSelectedBoundingBox(world, pos)
                    .grow(BlockOutlineModule.FILL_INFLATE).offset(-d0, -d1, -d2);

            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            GlStateManager.disableTexture2D();
            GlStateManager.depthMask(false);   // like vanilla: don't write depth (depth TEST stays on -> no x-ray)

            // ---- outline (recoloured + re-widthed) ----
            int argb = BlockOutlineModule.outlineColor(VANILLA_OUTLINE);
            float w = BlockOutlineModule.lineWidth(VANILLA_WIDTH);
            GlStateManager.glLineWidth(Math.max(1f, w));
            RenderGlobal.drawSelectionBoundingBox(base,
                    ((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f,
                    (argb & 0xFF) / 255f, ((argb >>> 24) & 0xFF) / 255f);

            // ---- optional translucent fill of the targeted block ----
            int fill = m.fillColor();
            if (fill != 0) {
                drawFilledBox(base, fill);
            }

            GlStateManager.depthMask(true);
            GlStateManager.enableTexture2D();
            GlStateManager.disableBlend();
            GlStateManager.glLineWidth(1f);
            GlStateManager.color(1f, 1f, 1f, 1f);
        } catch (Throwable t) {
            // never take the world render down; leave GL in a sane state
            try {
                GlStateManager.depthMask(true);
                GlStateManager.enableTexture2D();
                GlStateManager.disableBlend();
                GlStateManager.color(1f, 1f, 1f, 1f);
            } catch (Throwable ignored) {}
        }
    }

    /** Six {@code POSITION_COLOR} quads of a translucent axis-aligned box (depth test as left by the caller). */
    private static void drawFilledBox(AxisAlignedBB b, int argb) {
        int a = (argb >>> 24) & 0xFF, r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, bl = argb & 0xFF;
        Tessellator tess = Tessellator.getInstance();
        BufferBuilder wr = tess.getBuffer();
        wr.begin(7, DefaultVertexFormats.POSITION_COLOR);
        // bottom (minY) / top (maxY)
        wr.pos(b.minX, b.minY, b.minZ).color(r, g, bl, a).endVertex();
        wr.pos(b.maxX, b.minY, b.minZ).color(r, g, bl, a).endVertex();
        wr.pos(b.maxX, b.minY, b.maxZ).color(r, g, bl, a).endVertex();
        wr.pos(b.minX, b.minY, b.maxZ).color(r, g, bl, a).endVertex();

        wr.pos(b.minX, b.maxY, b.maxZ).color(r, g, bl, a).endVertex();
        wr.pos(b.maxX, b.maxY, b.maxZ).color(r, g, bl, a).endVertex();
        wr.pos(b.maxX, b.maxY, b.minZ).color(r, g, bl, a).endVertex();
        wr.pos(b.minX, b.maxY, b.minZ).color(r, g, bl, a).endVertex();
        // north (minZ) / south (maxZ)
        wr.pos(b.minX, b.minY, b.minZ).color(r, g, bl, a).endVertex();
        wr.pos(b.minX, b.maxY, b.minZ).color(r, g, bl, a).endVertex();
        wr.pos(b.maxX, b.maxY, b.minZ).color(r, g, bl, a).endVertex();
        wr.pos(b.maxX, b.minY, b.minZ).color(r, g, bl, a).endVertex();

        wr.pos(b.maxX, b.minY, b.maxZ).color(r, g, bl, a).endVertex();
        wr.pos(b.maxX, b.maxY, b.maxZ).color(r, g, bl, a).endVertex();
        wr.pos(b.minX, b.maxY, b.maxZ).color(r, g, bl, a).endVertex();
        wr.pos(b.minX, b.minY, b.maxZ).color(r, g, bl, a).endVertex();
        // west (minX) / east (maxX)
        wr.pos(b.minX, b.minY, b.maxZ).color(r, g, bl, a).endVertex();
        wr.pos(b.minX, b.maxY, b.maxZ).color(r, g, bl, a).endVertex();
        wr.pos(b.minX, b.maxY, b.minZ).color(r, g, bl, a).endVertex();
        wr.pos(b.minX, b.minY, b.minZ).color(r, g, bl, a).endVertex();

        wr.pos(b.maxX, b.minY, b.minZ).color(r, g, bl, a).endVertex();
        wr.pos(b.maxX, b.maxY, b.minZ).color(r, g, bl, a).endVertex();
        wr.pos(b.maxX, b.maxY, b.maxZ).color(r, g, bl, a).endVertex();
        wr.pos(b.maxX, b.minY, b.maxZ).color(r, g, bl, a).endVertex();
        tess.draw();
    }
}
