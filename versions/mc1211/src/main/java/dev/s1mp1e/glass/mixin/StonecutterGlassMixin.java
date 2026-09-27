package dev.s1mp1e.glass.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.s1mp1e.client.gui.GlassScrollbar;
import dev.s1mp1e.client.gui.GlassWidgets;
import dev.s1mp1e.client.gui.GlideProbe;
import dev.s1mp1e.client.gui.ScrollDragOwner;
import dev.s1mp1e.glass.render.ContainerGlass;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.item.ItemStack;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.StonecutterScreen;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Stonecutter recipe list → container glass (B/A panel + lattice + hover) + the shared vertical glass slider (C) + the
 * config-menu silky sub-pixel content glide (D). 1.21.1 (DrawContext) port of 26.2's {@code StonecutterScrollGlassMixin}
 * (26.2 drew through the retained {@code GuiGraphicsExtractor}; here everything is immediate-mode {@link DrawContext}).
 *
 * <h3>Why a dedicated mixin (the 1.19.2 / 1.21.1 blocker)</h3>
 * The generic {@code HandledScreenGlassMixin} redirects — and SWALLOWS — {@code HandledScreen.renderBackground}'s whole
 * {@code drawBackground} call, so it never runs and the stonecutter's recipe list, scroller and highlights vanish. This
 * mixin instead lets {@code drawBackground} run (the generic mixin routes stonecutter out of the swallow) and replaces
 * only the individual pieces: the body-texture blit → glass panel, the scroller sprite → glass slider, the recipe draws
 * → the eased sub-pixel glide.
 *
 * <h3>Seams (verified from 1.21.1 bytecode, yarn 1.21.1+build.3)</h3>
 * {@code drawBackground(DrawContext, float, int, int)} draws, in order: the body PNG ({@code drawTexture(Identifier,
 * IIIIII)} ordinal 0 at {@code x,y}), the scroller thumb ({@code drawGuiTexture(Identifier,IIII)} ordinal 0 at
 * {@code x+119, y+15 + (int)(41*scrollAmount), 12, 15}), then {@code renderRecipeBackground(ctx, mouseX, mouseY,
 * x+52, y+14, scrollOffset+12)} (pointer FIRST, then the grid origin) and {@code renderRecipeIcons(ctx, x+52, y+14, scrollOffset+12)}. {@code scrollAmount} is the
 * 0..1 scroll ratio; {@code scrollOffset} the row-aligned top cell index (= {@code (int)(scrollAmount*getMaxScroll())*4});
 * {@code getMaxScroll()} the off-screen row count; {@code shouldScroll()} true iff more than 12 recipes; the 4×3 grid is
 * 16 px wide / 18 px tall cells at {@code (x+52, y+14+2)}.
 */
@Mixin(StonecutterScreen.class)
public abstract class StonecutterGlassMixin implements GlideProbe, ScrollDragOwner {

    @Shadow private float scrollAmount;
    @Shadow private boolean mouseClicked;       // the vanilla scrollbar-drag flag
    @Shadow private int scrollOffset;
    @Shadow protected abstract int getMaxScroll();
    @Shadow private boolean shouldScroll() { return false; }

    @Unique private final ContainerGlass.State s1mp1e$glass = new ContainerGlass.State();
    @Unique private final GlassScrollbar s1mp1e$bar = new GlassScrollbar();
    @Unique private int s1mp1e$mouseX, s1mp1e$mouseY;
    /** true this frame when the recipe grid should glide sub-pixel (pipeline usable + an active, off-row scroll). */
    @Unique private boolean s1mp1e$sliding;
    /** eased scroll offset in px (0 = top) — the same value the glass thumb sits at. */
    @Unique private float s1mp1e$scrollPx;

    @Unique private static final int GRID_COLS = 4, CELL_W = 16, CELL_H = 18, VIS_ROWS = 3;

    /** Capture the pointer for the glass slider (the scroller redirect doesn't receive it). */
    @Inject(method = "drawBackground", at = @At("HEAD"))
    private void s1mp1e$captureMouse(DrawContext ctx, float delta, int mouseX, int mouseY, CallbackInfo ci) {
        s1mp1e$mouseX = mouseX;
        s1mp1e$mouseY = mouseY;
    }

    // ---- A/B: body PNG → container glass (panel + lattice + hover); recipe list still draws on top ----
    @Redirect(method = "drawBackground",
            at = @At(value = "INVOKE", ordinal = 0,
                     target = "Lnet/minecraft/client/gui/DrawContext;drawTexture(Lnet/minecraft/util/Identifier;IIIIII)V"))
    private void s1mp1e$glassBody(DrawContext ctx, Identifier tex, int x, int y, int u, int v, int w, int h) {
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) {
            ctx.drawTexture(tex, x, y, u, v, w, h);
            return;
        }
        HandledScreenAccessor a = (HandledScreenAccessor) (Object) this;
        ContainerGlass.draw(s1mp1e$glass, ctx, x, y, w, h,
                a.s1mp1e$handler().slots, a.s1mp1e$cursorDragSlots(), a.s1mp1e$cursorDragging(),
                s1mp1e$mouseX, s1mp1e$mouseY);
    }

    // ---- C: scroller thumb → glass slider (also computes the per-frame glide state for D) ----
    @Redirect(method = "drawBackground",
            at = @At(value = "INVOKE", ordinal = 0,
                     target = "Lnet/minecraft/client/gui/DrawContext;drawGuiTexture(Lnet/minecraft/util/Identifier;IIII)V"))
    private void s1mp1e$glassScroller(DrawContext ctx, Identifier tex, int bx, int by, int bw, int bh) {
        s1mp1e$sliding = false;
        s1mp1e$scrollPx = 0f;
        boolean active = shouldScroll();
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) {
            ctx.drawGuiTexture(tex, bx, by, bw, bh);
            return;
        }
        HandledScreenAccessor a = (HandledScreenAccessor) (Object) this;
        float cx = a.s1mp1e$x() + 119 + 6f;       // 12-wide thumb at x+119 → centre +6
        float trackTop = a.s1mp1e$y() + 15f;
        ctx.draw();   // land any pending batch under the immediate-GL slider
        // Point the thumb at the ROW-ALIGNED logical ratio (scrollOffset/4 of getMaxScroll), not the continuous
        // scrollAmount: after a drag scrollAmount rests between rows while vanilla hit-tests the rounded row, so easing
        // to scrollAmount would leave the grid drawn off the clickable rows. At rest thumb, grid and hit-test agree.
        int maxScroll = Math.max(0, getMaxScroll());
        int row = maxScroll <= 0 ? 0 : Math.min(maxScroll, Math.max(0, scrollOffset / GRID_COLS));
        float ratio = maxScroll <= 0 ? 0f : (float) row / maxScroll;
        GlassScrollbar.run(s1mp1e$bar, ctx, cx, trackTop, 41f, 15f,
                ratio, active, mouseClicked && active, s1mp1e$mouseY, 1f);
        if (active && maxScroll > 0) {
            float easedRows = s1mp1e$bar.pos() * maxScroll;
            if (Math.abs(easedRows - row) > 0.02f) {
                s1mp1e$sliding = true;
                s1mp1e$scrollPx = easedRows * CELL_H;
            }
        }
    }

    // ---- D: draw every recipe from row 0, translated by the eased scroll, clipped to the 3-row window ----
    @WrapOperation(method = "drawBackground",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screen/ingame/StonecutterScreen;renderRecipeBackground(Lnet/minecraft/client/gui/DrawContext;IIIII)V"))
    private void s1mp1e$slideRecipeBg(StonecutterScreen self, DrawContext ctx, int mouseX, int mouseY,
                                      int baseX, int baseY, int endIndex, Operation<Void> op) {
        // renderRecipeBackground(ctx, mouseX, mouseY, x, y, scrollEnd) — the pointer comes BEFORE the grid origin
        // (decompiled 1.21.1 call: renderRecipeBackground(context, mouseX, mouseY, x+52, y+14, scrollOffset+12)).
        if (!s1mp1e$sliding) { op.call(self, ctx, mouseX, mouseY, baseX, baseY, endIndex); return; }
        int saved = scrollOffset;
        scrollOffset = 0;
        ctx.enableScissor(baseX, baseY, baseX + GRID_COLS * CELL_W, baseY + VIS_ROWS * CELL_H + 2);
        ctx.getMatrices().push();
        ctx.getMatrices().translate(0f, -s1mp1e$scrollPx, 0f);
        try {
            // no hover highlight mid-glide (vanilla would test the pointer against the UNtranslated cells)
            op.call(self, ctx, -10000, -10000, baseX, baseY, Integer.MAX_VALUE);
        } finally {
            ctx.getMatrices().pop();
            ctx.disableScissor();
            scrollOffset = saved;
        }
    }

    /** Same sub-pixel slide + clip for the recipe RESULT icons drawn on top of the buttons. */
    @WrapOperation(method = "drawBackground",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screen/ingame/StonecutterScreen;renderRecipeIcons(Lnet/minecraft/client/gui/DrawContext;III)V"))
    private void s1mp1e$slideRecipeIcons(StonecutterScreen self, DrawContext ctx, int baseX, int baseY,
                                         int endIndex, Operation<Void> op) {
        if (!s1mp1e$sliding) { op.call(self, ctx, baseX, baseY, endIndex); return; }
        int saved = scrollOffset;
        scrollOffset = 0;
        ctx.enableScissor(baseX, baseY, baseX + GRID_COLS * CELL_W, baseY + VIS_ROWS * CELL_H + 2);
        ctx.getMatrices().push();
        ctx.getMatrices().translate(0f, -s1mp1e$scrollPx, 0f);
        try {
            op.call(self, ctx, baseX, baseY, Integer.MAX_VALUE);
        } finally {
            ctx.getMatrices().pop();
            ctx.disableScissor();
            scrollOffset = saved;
        }
        // iOS-26 scroll-edge whisper over the moving window (fresh composite grab: blurs the recipes, never itself)
        if (GlassProgram.edgeUsable()) {
            ctx.draw();
            SceneCapture.grabNow();
            int maxScroll = Math.max(0, getMaxScroll());
            float rows = s1mp1e$scrollPx / CELL_H;
            float topK = rows > 0.03f ? 1f : 0f;
            float botK = rows < maxScroll - 0.03f ? 1f : 0f;
            GlassWidgets.scrollEdges(baseX, baseY, baseX + GRID_COLS * CELL_W, baseY + VIS_ROWS * CELL_H + 2, 5f,
                    topK, botK, 1f);
        }
    }

    /** No recipe tooltip mid-glide: vanilla maps the pointer to the row-aligned cells, not the gliding ones. */
    @Redirect(method = "drawMouseoverTooltip",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/DrawContext;drawItemTooltip(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/item/ItemStack;II)V"))
    private void s1mp1e$recipeTooltip(DrawContext ctx, TextRenderer font, ItemStack stack, int x, int y) {
        if (!s1mp1e$sliding) ctx.drawItemTooltip(font, stack, x, y);
    }

    @Override
    public boolean s1mp1e$probeGliding() { return s1mp1e$sliding; }

    @Override
    public void s1mp1e$endScrollDrag() { mouseClicked = false; }

    @Override
    public float s1mp1e$probeOffsetPx() { return s1mp1e$bar.pos() * Math.max(0, getMaxScroll()) * CELL_H; }

    /** A click mid-glide snaps to the target row first, so it always selects the recipe drawn under the cursor. */
    @Inject(method = "mouseClicked(DDI)Z", at = @At("HEAD"))
    private void s1mp1e$snapOnClick(double mx, double my, int button, CallbackInfoReturnable<Boolean> cir) {
        if (s1mp1e$sliding) {
            s1mp1e$bar.snapToTarget();
            s1mp1e$sliding = false;
        }
    }
}
