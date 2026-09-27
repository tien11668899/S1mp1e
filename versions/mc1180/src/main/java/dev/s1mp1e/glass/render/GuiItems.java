package dev.s1mp1e.glass.render;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.DiffuseLighting;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;

/**
 * 1.19.2 stand-in for 1.20's {@code DrawContext.drawItem} / {@code drawItemInSlot} under a translate/scale.
 *
 * <p><b>Why a helper.</b> On 1.19.2 the GUI item model ({@code ItemRenderer.renderGuiItemModel}) reads
 * {@link RenderSystem#getModelViewStack()} and ignores any caller {@link MatrixStack}, so a module that moves
 * or scales its items (glass hotbar, Inventory HUD, Armor HUD) has to mirror that transform onto the
 * RenderSystem model-view: {@link #beginItemTransform} / {@link #endItemTransform}, always paired with
 * try/finally.
 *
 * <p><b>The count text (Tier-A fix).</b> {@code renderGuiItemOverlay} draws the stack count through a fresh
 * {@code new MatrixStack()} into {@code VertexConsumerProvider.immediate(...)}; ImmediatelyFast's HUD batching
 * redirects exactly that call site into its item-overlay batch, which is drawn only when the HUD method returns,
 * after the caller has popped its scale, so the number comes out small and misplaced. {@link #drawStack}
 * therefore calls the 5-argument overload with an empty count label (the durability and cooldown bars stay)
 * and redraws the count itself into its OWN immediate, flushed on the spot. That flush uses the current
 * RenderSystem model-view, so inside a {@link #beginItemTransform} block the count already gets the item
 * transform and only the vanilla z lift ({@code zOffset + 200}) is baked into the text matrix - the same
 * recipe as the shipped 1.19.2 glass jar ({@code new MatrixStack(); translate(0, 0, 200)}).
 */
public final class GuiItems {

    private GuiItems() {}

    /** Full-bright light value vanilla uses for the count text (LightmapTextureManager.MAX_LIGHT_COORDINATE). */
    private static final int FULL_BRIGHT = 15728880;

    /** Number of {@link #beginItemTransform} blocks currently open (render thread only). */
    private static int depth;

    /**
     * Push the RenderSystem model-view, apply {@code translate(tx, ty) * scale(sx, sy)} and upload it.
     * Every call must be matched by {@link #endItemTransform()} in a finally block.
     */
    public static void beginItemTransform(float tx, float ty, float sx, float sy) {
        MatrixStack mv = RenderSystem.getModelViewStack();
        mv.push();
        depth++;
        mv.translate(tx, ty, 0.0);
        mv.scale(sx, sy, 1f);
        RenderSystem.applyModelViewMatrix();
    }

    /** Pop the entry pushed by {@link #beginItemTransform} and re-upload the model-view. */
    public static void endItemTransform() {
        if (depth <= 0) return;   // unmatched end: never pop an entry this helper did not push
        depth--;
        RenderSystem.getModelViewStack().pop();
        RenderSystem.applyModelViewMatrix();
    }

    /** True while at least one {@link #beginItemTransform} block is open. */
    public static boolean inItemTransform() {
        return depth > 0;
    }

    /**
     * Draw one GUI item (model, durability/cooldown bars and stack count) at (x, y) in the current item space.
     *
     * @param e                   the entity the model is resolved for (may be null)
     * @param bakedTranslateScale {tx, ty, sx, sy} to bake into the COUNT TEXT matrix when this call is NOT inside
     *                            a {@link #beginItemTransform} block (there the RenderSystem model-view already
     *                            carries the transform, so baking it again would double it and it is ignored);
     *                            null means none
     */
    public static void drawStack(LivingEntity e, ItemStack s, int x, int y, float[] bakedTranslateScale) {
        if (s == null || s.isEmpty()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        ItemRenderer ir = mc.getItemRenderer();
        TextRenderer tr = mc.textRenderer;

        // Item-state priming (1.20.1 hotbar hygiene): GUI item program, GUI lighting, and a white shader colour
        // set twice so a stale cached colour can never tint the item black.
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        DiffuseLighting.enableGuiDepthLighting();
        RenderSystem.setShaderColor(0f, 0f, 0f, 0f);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);

        ir.renderInGuiWithOverrides(e, s, x, y, 0);
        // Empty count label: durability + cooldown still draw, vanilla's own count does not.
        ir.renderGuiItemOverlay(tr, s, x, y, "");

        int count = s.getCount();
        if (count <= 1) return;
        String str = String.valueOf(count);

        MatrixStack tm = new MatrixStack();
        if (depth == 0 && bakedTranslateScale != null && bakedTranslateScale.length >= 4) {
            tm.translate(bakedTranslateScale[0], bakedTranslateScale[1], 0.0);
            tm.scale(bakedTranslateScale[2], bakedTranslateScale[3], 1f);
        }
        tm.translate(0.0, 0.0, ir.zOffset + 200.0f);

        VertexConsumerProvider.Immediate imm = VertexConsumerProvider.immediate(Tessellator.getInstance().getBuffer());
        try {
            tr.draw(str, (float) (x + 19 - 2 - tr.getWidth(str)), (float) (y + 6 + 3), 0xFFFFFF, true,
                    tm.peek().getPositionMatrix(), imm, false, 0, FULL_BRIGHT);
        } finally {
            imm.draw();
        }
    }

    /**
     * Draw only the GUI item MODEL at (x, y) in the current item space — no stack count, no durability /
     * cooldown overlay bar. The 1.19.2 stand-in for 1.20's {@code DrawContext.drawItem(stack, x, y)} used by
     * the Armor HUD (its silhouette outline conveys durability, so vanilla's green bar and number must not
     * show). Same item-state priming as {@link #drawStack} so an item drawn straight after the raw-GL glass
     * never renders black.
     *
     * @param e the entity the model is resolved for (may be null — matches 1.20's entity-less overload)
     */
    public static void drawModel(LivingEntity e, ItemStack s, int x, int y) {
        if (s == null || s.isEmpty()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        ItemRenderer ir = mc.getItemRenderer();

        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        DiffuseLighting.enableGuiDepthLighting();
        RenderSystem.setShaderColor(0f, 0f, 0f, 0f);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);

        ir.renderInGuiWithOverrides(e, s, x, y, 0);
    }
}
