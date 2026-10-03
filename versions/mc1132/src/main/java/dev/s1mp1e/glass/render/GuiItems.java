package dev.s1mp1e.glass.render;

import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;

/**
 * 1.13.2 stand-in for 1.20's {@code DrawContext.drawItem} / {@code drawItemInSlot} under a translate/scale (the name
 * and calls the code shared with the newer lines uses).
 *
 * <p>On 1.13.2 the GUI item model ({@code HeldItemRenderer.renderGuiItemModel}) and the count / durability overlay
 * ({@code renderGuiItemOverlay}) both draw under the fixed-function GL model-view ({@code GlStateManager.pushMatrix /
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
        GlStateManager.pushMatrix();
        depth++;
        GlStateManager.translate(tx, ty, 0f);
        GlStateManager.scale(sx, sy, 1f);
    }

    /** Pop the entry pushed by {@link #beginItemTransform}. */
    public static void endItemTransform() {
        if (depth <= 0) return;   // unmatched end: never pop an entry this helper did not push
        depth--;
        GlStateManager.popMatrix();
    }

    /** True while at least one {@link #beginItemTransform} block is open. */
    public static boolean inItemTransform() {
        return depth > 0;
    }

    /**
     * Draw one GUI item (model, durability/cooldown bars and stack count) at (x, y) in the current item space.
     *
     * @param e                   the entity the model is resolved for (may be null)
     * @param bakedTranslateScale unused on 1.13.2 (the count text follows the GL model-view like the model)
     */
    public static void drawStack(LivingEntity e, ItemStack s, int x, int y, float[] bakedTranslateScale) {
        if (s == null || s.isEmpty()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        HeldItemRenderer ir = mc.getHeldItemRenderer();
        GlStateManager.color(1f, 1f, 1f, 1f);
        GlStateManager.enableDepthTest();
        if (e != null) ir.method_19374(e, s, x, y);
        else ir.method_19397(s, x, y);
        ir.method_19383(mc.textRenderer, s, x, y);
    }

    /** Draw only the GUI item MODEL at (x, y) in the current item space — no count, no durability bar. */
    public static void drawModel(LivingEntity e, ItemStack s, int x, int y) {
        if (s == null || s.isEmpty()) return;
        HeldItemRenderer ir = MinecraftClient.getInstance().getHeldItemRenderer();
        GlStateManager.color(1f, 1f, 1f, 1f);
        if (e != null) ir.method_19374(e, s, x, y);
        else ir.method_19397(s, x, y);
    }
}
