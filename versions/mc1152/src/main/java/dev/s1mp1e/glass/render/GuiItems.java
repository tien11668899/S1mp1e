package dev.s1mp1e.glass.render;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;

/**
 * 1.15.2 stand-in for 1.20's {@code DrawContext.drawItem} / {@code drawItemInSlot} under a translate/scale (the name
 * and calls the code shared with the newer lines uses).
 *
 * <p>On 1.15.2 the GUI item model ({@code ItemRenderer.renderGuiItemModel}) and the count / durability overlay
 * ({@code renderGuiItemOverlay}) both draw under the fixed-function GL model-view ({@code RenderSystem.pushMatrix /
 * translatef / scalef}) and are flushed on the spot, so a transform is simply a pushed model-view — no model-view
 * stack to upload and no separately baked text matrix as on 1.17+.
 */
public final class GuiItems {

    private GuiItems() {}

    /** Number of {@link #beginItemTransform} blocks currently open (render thread only). */
    private static int depth;

    /**
     * Push the GL model-view and apply {@code translate(tx, ty) * scale(sx, sy)}.
     * Every call must be matched by {@link #endItemTransform()} in a finally block.
     */
    public static void beginItemTransform(float tx, float ty, float sx, float sy) {
        RenderSystem.pushMatrix();
        depth++;
        RenderSystem.translatef(tx, ty, 0f);
        RenderSystem.scalef(sx, sy, 1f);
    }

    /** Pop the entry pushed by {@link #beginItemTransform}. */
    public static void endItemTransform() {
        if (depth <= 0) return;   // unmatched end: never pop an entry this helper did not push
        depth--;
        RenderSystem.popMatrix();
    }

    /** True while at least one {@link #beginItemTransform} block is open. */
    public static boolean inItemTransform() {
        return depth > 0;
    }

    /**
     * Draw one GUI item (model, durability/cooldown bars and stack count) at (x, y) in the current item space.
     *
     * @param e                   the entity the model is resolved for (may be null)
     * @param bakedTranslateScale unused on 1.15.2 (the count text follows the GL model-view like the model)
     */
    public static void drawStack(LivingEntity e, ItemStack s, int x, int y, float[] bakedTranslateScale) {
        if (s == null || s.isEmpty()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        ItemRenderer ir = mc.getItemRenderer();
        RenderSystem.color4f(1f, 1f, 1f, 1f);
        RenderSystem.enableDepthTest();
        if (e != null) ir.renderGuiItem(e, s, x, y);
        else ir.renderGuiItem(s, x, y);
        ir.renderGuiItemOverlay(mc.textRenderer, s, x, y);
    }

    /** Draw only the GUI item MODEL at (x, y) in the current item space — no count, no durability bar. */
    public static void drawModel(LivingEntity e, ItemStack s, int x, int y) {
        if (s == null || s.isEmpty()) return;
        ItemRenderer ir = MinecraftClient.getInstance().getItemRenderer();
        RenderSystem.color4f(1f, 1f, 1f, 1f);
        if (e != null) ir.renderGuiItem(e, s, x, y);
        else ir.renderGuiItem(s, x, y);
    }
}
