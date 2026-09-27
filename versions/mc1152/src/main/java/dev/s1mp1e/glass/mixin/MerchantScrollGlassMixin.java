package dev.s1mp1e.glass.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.s1mp1e.client.gui.GlassScrollbar;
import dev.s1mp1e.client.gui.GlassWidgets;
import dev.s1mp1e.glass.render.GlassProgram;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.screen.ingame.MerchantScreen;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.MathHelper;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TraderOfferList;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Villager / merchant trade list &rarr; the shared vertical glass slider (C) + silky sub-pixel trade glide (D). The
 * 1.15.2 (FF-Fabric, immediate-mode) port of 1.20.1's {@code MerchantScrollGlassMixin}, adapted to the fixed-function
 * {@code render}/{@code method_20221} of this version's {@link MerchantScreen}.
 *
 * <p>Merchant is NOT excluded from the shared container-glass swallow, so it already gets the frosted panel + slot
 * lattice + hover pill from {@link HandledScreenGlassMixin} (its trade LIST lives in {@code render}, which the
 * {@code drawBackground} swallow never touches, so nothing is erased). This mixin adds only the scrollbar look and the
 * content glide.
 *
 * <h3>Scrollbar (C)</h3>
 * The frame-primary hook is the {@code method_20221(i, j, offers)} INVOKE inside {@code render} (the vanilla scroller
 * draw), redirected here. Instead of the 6&times;27 sprite it paints the shared 15&nbsp;px glass thumb (matching
 * creative / stonecutter / loom &mdash; the 27&nbsp;px sprite made the held lens look oversized), travel grown to 124 so
 * the thumb still spans the 139&nbsp;px track. Vanilla's own {@code mouseDragged}/{@code mouseScrolled} keep driving
 * {@code indexStartOffset} by whole trades; the glass thumb eases toward that row ratio (config-menu curve, tau =
 * 90&nbsp;ms) with the held refracting lens and 1:1 rubber-banded drag.
 *
 * <h3>Silky content (D)</h3>
 * Vanilla remaps the 7 fixed trade rows by whole trades on scroll. Here the trade CONTENT glides sub-pixel with the SAME
 * eased value the thumb uses. The redirect computes the glide state each frame; while the eased offset differs from the
 * logical {@code indexStartOffset} row this mixin suppresses the vanilla trade loop's own cost/arrow/result draws
 * (redirecting {@code method_20222} + {@code method_20223} + the two {@code ItemRenderer} draws in {@code render}) and,
 * right after the loop (the first {@code selectedIndex} read), redraws the visible trades itself at absolute rows under a
 * {@code RenderSystem.translatef(0, -frac, 0)} &mdash; a TRUE sub-pixel glide, because {@code ItemRenderer.renderGuiItem}
 * / {@code blit} all draw through the GL modelview &mdash; clipped to the trade window with one extra row so no edge gap
 * shows. A mid-glide click snaps to the target row first so a trade click always selects the trade under the cursor. The
 * trade-select buttons ({@code WidgetButtonPage}) are vanilla click regions drawn by {@code super.render} and are not
 * glassed, so &mdash; as in 1.20.1 &mdash; only the item content glides, re-aligning at rest.
 */
@Mixin(MerchantScreen.class)
public abstract class MerchantScrollGlassMixin {

    // Trade-row geometry (vanilla render): row 0 item Y = top+19, rows step 20 px, 7 rows visible.
    @Unique private static final int MC_VIS = 7, MC_ROW_Y0 = 19, MC_ROW_H = 20;
    @Unique private static final int MC_COSTA_X = 10, MC_COSTB_X = 40, MC_RESULT_X = 73;
    @Unique private static final int MC_WINDOW_Y = 18, MC_WINDOW_H = 139;

    @Shadow private int indexStartOffset;
    @Shadow private boolean scrolling;
    @Shadow private boolean canScroll(int i) { throw new AssertionError(); }
    // Private target methods of MerchantScreen -> @Shadow with a stub body (the verified private-method-shadow pattern).
    @Shadow private void method_20221(int i, int j, TraderOfferList offers) { throw new AssertionError(); }
    @Shadow private void method_20222(ItemStack adjusted, ItemStack original, int x, int y) { throw new AssertionError(); }
    @Shadow private void method_20223(TradeOffer offer, int leftPos, int y) { throw new AssertionError(); }

    @Unique private GlassScrollbar s1mp1e$scrollbar;
    @Unique private boolean s1mp1e$sliding;
    @Unique private int s1mp1e$glideBase;
    @Unique private float s1mp1e$glideFracPx;

    @Unique private int s1mp1e$px() { return ((ContainerScreenTopAccessor) (Object) this).s1mp1e$left(); }
    @Unique private int s1mp1e$py() { return ((ContainerScreenTopAccessor) (Object) this).s1mp1e$top(); }

    /** Scroller sprite -> glass slider + the per-frame glide-state computation (runs before the trade loop in render). */
    @Redirect(method = "render",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screen/ingame/MerchantScreen;"
                            + "method_20221(IILnet/minecraft/village/TraderOfferList;)V"))
    private void s1mp1e$scroller(MerchantScreen self, int i, int j, TraderOfferList offers,
                                 int mouseX, int mouseY, float delta) {
        s1mp1e$sliding = false;
        int size = offers.size();
        if (!GlassProgram.ensureReady() || !GlassProgram.usable() || size <= MC_VIS) {
            this.method_20221(i, j, offers);   // vanilla scroller sprite (self == this; shadow)
            return;
        }
        if (s1mp1e$scrollbar == null) s1mp1e$scrollbar = new GlassScrollbar();
        int maxRows = size - MC_VIS;
        float ratio = maxRows <= 0 ? 0f : MathHelper.clamp((float) indexStartOffset / maxRows, 0f, 1f);
        double mouseYScaled = MinecraftClient.getInstance().mouse.getY()
                * MinecraftClient.getInstance().getWindow().getScaledHeight()
                / Math.max(1, MinecraftClient.getInstance().getWindow().getHeight());
        // merchant track: 15 px thumb, top at top+18, thumb-top travel 124 (139 area - 15 thumb).
        GlassScrollbar.run(s1mp1e$scrollbar, i + 94 + 3f, j + 18f, 124f, 15f,
                ratio, true, scrolling, mouseYScaled, 1.0f);
        if (maxRows > 0) {
            float easedTrades = s1mp1e$scrollbar.pos() * maxRows;
            if (Math.abs(easedTrades - indexStartOffset) > 0.02f) {
                s1mp1e$sliding = true;
                s1mp1e$glideBase = MathHelper.clamp((int) Math.floor(easedTrades), 0, maxRows);
                s1mp1e$glideFracPx = (easedTrades - s1mp1e$glideBase) * MC_ROW_H;
            }
        }
    }

    // ---- suppress the vanilla trade content during a glide (the eased overlay redraws it) --------------------------

    @Redirect(method = "render",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screen/ingame/MerchantScreen;"
                            + "method_20222(Lnet/minecraft/item/ItemStack;Lnet/minecraft/item/ItemStack;II)V"))
    private void s1mp1e$costA(MerchantScreen self, ItemStack adjusted, ItemStack original, int x, int y) {
        if (!s1mp1e$sliding) this.method_20222(adjusted, original, x, y);
    }

    @Redirect(method = "render",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screen/ingame/MerchantScreen;"
                            + "method_20223(Lnet/minecraft/village/TradeOffer;II)V"))
    private void s1mp1e$arrow(MerchantScreen self, TradeOffer offer, int leftPos, int y) {
        if (!s1mp1e$sliding) this.method_20223(offer, leftPos, y);
    }

    @Redirect(method = "render",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/render/item/ItemRenderer;"
                            + "renderGuiItem(Lnet/minecraft/item/ItemStack;II)V"))
    private void s1mp1e$tradeItem(ItemRenderer ir, ItemStack stack, int x, int y) {
        if (!s1mp1e$sliding) ir.renderGuiItem(stack, x, y);
    }

    @Redirect(method = "render",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/render/item/ItemRenderer;"
                            + "renderGuiItemOverlay(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/item/ItemStack;II)V"))
    private void s1mp1e$tradeOverlay(ItemRenderer ir, TextRenderer font, ItemStack stack, int x, int y) {
        if (!s1mp1e$sliding) ir.renderGuiItemOverlay(font, stack, x, y);
    }

    /** Redraw the visible trades at absolute rows, translated by the eased offset, clipped to the trade window. Injected
     *  right after the trade loop (the first {@code selectedIndex} read), before drawLevelInfo / tooltips. */
    @Inject(method = "render",
            at = @At(value = "FIELD", opcode = Opcodes.GETFIELD, ordinal = 0,
                     target = "Lnet/minecraft/client/gui/screen/ingame/MerchantScreen;selectedIndex:I"))
    private void s1mp1e$overlay(int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!s1mp1e$sliding) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        TraderOfferList offers = ((net.minecraft.container.MerchantContainer)
                ((MerchantScreen) (Object) this).getContainer()).getRecipes();
        int size = offers.size();
        int px = s1mp1e$px(), py = s1mp1e$py();
        ItemRenderer ir = mc.getItemRenderer();
        TextRenderer font = mc.textRenderer;

        int x0 = px + 8, x1 = px + 92;
        int y0 = py + MC_WINDOW_Y, y1 = py + MC_WINDOW_Y + MC_WINDOW_H;
        GlassWidgets.beginScissor(x0, y0, x1, y1);
        RenderSystem.pushMatrix();
        RenderSystem.translatef(0f, -s1mp1e$glideFracPx, 0f);   // sub-pixel glide (items draw through the GL modelview)
        float savedZ = ir.zOffset;
        ir.zOffset = 100f;                                       // lift items over the frosted panel (vanilla parity)
        try {
            for (int tr = 0; tr <= MC_VIS; tr++) {
                int idx = s1mp1e$glideBase + tr;
                if (idx < 0 || idx >= size) continue;
                TradeOffer offer = offers.get(idx);
                int y = py + MC_ROW_Y0 + tr * MC_ROW_H;
                this.method_20222(offer.getAdjustedFirstBuyItem(), offer.getOriginalFirstBuyItem(), px + MC_COSTA_X, y);
                ItemStack costB = offer.getSecondBuyItem();
                if (!costB.isEmpty()) {
                    ir.renderGuiItem(costB, px + MC_COSTB_X, y);
                    ir.renderGuiItemOverlay(font, costB, px + MC_COSTB_X, y);
                }
                this.method_20223(offer, px, y);
                ItemStack sell = offer.getMutableSellItem();
                ir.renderGuiItem(sell, px + MC_RESULT_X, y);
                ir.renderGuiItemOverlay(font, sell, px + MC_RESULT_X, y);
            }
        } finally {
            ir.zOffset = savedZ;
            RenderSystem.popMatrix();
            GlassWidgets.endScissor();
        }

        float topK = s1mp1e$glideBase > 0 || s1mp1e$glideFracPx > 0.5f ? 1f : 0f;
        float botK = (s1mp1e$glideBase + MC_VIS) < size ? 1f : 0f;
        GlassWidgets.scrollEdges(x0, y0, x1, y1, 6f, topK, botK, 1.0f);
    }

    /** A click mid-glide snaps to the target row first, so it always selects the trade drawn under the cursor. */
    @Inject(method = "mouseClicked", at = @At("HEAD"))
    private void s1mp1e$snapOnClick(double mx, double my, int button, CallbackInfoReturnable<Boolean> cir) {
        if (s1mp1e$sliding && s1mp1e$scrollbar != null) {
            s1mp1e$scrollbar.snapToTarget();
            s1mp1e$sliding = false;
        }
    }
}
