package dev.s1mp1e.glass.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.s1mp1e.client.gui.GlassScrollbar;
import dev.s1mp1e.client.gui.GlassWidgets;
import dev.s1mp1e.client.gui.GlideProbe;
import dev.s1mp1e.client.gui.GuiScissor;
import dev.s1mp1e.client.gui.ScrollDragOwner;
import dev.s1mp1e.glass.render.ContainerGlass;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.gui.screen.ingame.StonecutterScreen;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
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
 * Stonecutter recipe list → container glass (panel + lattice + hover) + the shared vertical glass slider (C) + the
 * config-menu silky sub-pixel content glide (D). 1.17.1 ({@link MatrixStack}, core profile) port of 26.2's
 * {@code StonecutterScrollGlassMixin}, structured like the verified 1.21.1 sibling.
 *
 * <h3>Why a dedicated mixin (the KNOWN BLOCKER)</h3>
 * The generic {@code HandledScreenGlassMixin} swallowed {@code HandledScreen.render}'s whole {@code drawBackground}
 * call, so the stonecutter's recipe list, scroller and even its dim ({@code renderBackground} runs INSIDE the 1.17.1
 * stonecutter {@code drawBackground}) vanished. The generic mixin now routes the stonecutter out of the swallow; this
 * mixin replaces only the pieces: the body blit → {@link ContainerGlass}, the scroller sprite → the glass slider, the
 * recipe draws → the eased sub-pixel glide.
 *
 * <h3>Seams (javap-verified, 1.17.1 / yarn build.65)</h3>
 * {@code drawBackground(MatrixStack,FII)}: {@code renderBackground} (dim), then {@code drawTexture(MatrixStack,IIIIII)}
 * ordinal 0 = the body at {@code x,y}, ordinal 1 = the scroller at {@code (x+119, y+15+(int)(41*scrollAmount), 176|188,
 * 0, 12, 15)}, then {@code renderRecipeBackground(matrices, mouseX, mouseY, x+52, y+14, scrollOffset+12)} (pointer
 * FIRST) and {@code renderRecipeIcons(x+52, y+14, scrollOffset+12)} (no MatrixStack — items compose the RenderSystem
 * model-view stack). The 4×3 grid is 16×18 cells at {@code (x+52, y+14+2)}; {@code scrollOffset} is the row-aligned
 * top cell index, {@code getMaxScroll()} the off-screen row count, {@code shouldScroll()} = more than 12 recipes.
 *
 * <h3>Glide (D)</h3>
 * While the eased thumb value differs from the logical row, both recipe draws run with {@code scrollOffset = 0} and an
 * unbounded end, translated up by the eased offset (buttons through the {@code matrices} arg, icons through the
 * RenderSystem model-view — both compose into the same shader transform), scissored (absolute: drawBackground runs at
 * pose identity) to the 3-row window, with no hover highlight / recipe tooltip (vanilla would test the pointer against
 * the row-aligned cells). A click mid-glide snaps to the target row first.
 */
@Mixin(StonecutterScreen.class)
public abstract class StonecutterScrollGlassMixin implements GlideProbe, ScrollDragOwner {

    @Unique private static final Identifier S1MP1E_TEX = new Identifier("textures/gui/container/stonecutter.png");
    @Unique private static final int GRID_COLS = 4, CELL_W = 16, CELL_H = 18, VIS_ROWS = 3;

    @Shadow private float scrollAmount;
    @Shadow private boolean mouseClicked;       // the vanilla scrollbar-drag flag
    @Shadow private int scrollOffset;
    @Shadow protected abstract int getMaxScroll();
    @Shadow private boolean shouldScroll() { throw new AssertionError(); }

    @Unique private final ContainerGlass.State s1mp1e$glass = new ContainerGlass.State();
    @Unique private final GlassScrollbar s1mp1e$bar = new GlassScrollbar();
    @Unique private int s1mp1e$mouseX, s1mp1e$mouseY;
    /** true this frame when the recipe grid glides sub-pixel (pipeline usable + an active, off-row scroll). */
    @Unique private boolean s1mp1e$sliding;
    /** eased scroll offset in px (0 = top) — the same value the glass thumb sits at. */
    @Unique private float s1mp1e$scrollPx;

    @Unique private HandledScreenAccessor s1mp1e$acc() { return (HandledScreenAccessor) (Object) this; }

    /** Capture the pointer for the glass slider (the scroller redirect doesn't receive it). */
    @Inject(method = "drawBackground", at = @At("HEAD"))
    private void s1mp1e$captureMouse(MatrixStack matrices, float delta, int mouseX, int mouseY, CallbackInfo ci) {
        s1mp1e$mouseX = mouseX;
        s1mp1e$mouseY = mouseY;
    }

    // ---- A/B: body PNG → container glass (panel + lattice + hover); the recipe list still draws on top ----
    @Redirect(method = "drawBackground",
            at = @At(value = "INVOKE", ordinal = 0,
                     target = "Lnet/minecraft/client/gui/screen/ingame/StonecutterScreen;"
                            + "drawTexture(Lnet/minecraft/client/util/math/MatrixStack;IIIIII)V"))
    private void s1mp1e$glassBody(StonecutterScreen self, MatrixStack matrices, int x, int y, int u, int v, int w, int h) {
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) {
            self.drawTexture(matrices, x, y, u, v, w, h);
            return;
        }
        HandledScreenAccessor a = s1mp1e$acc();
        ContainerGlass.draw(s1mp1e$glass, x, y, w, h, a.s1mp1e$handler().slots, a.s1mp1e$cursorDragSlots(),
                a.s1mp1e$cursorDragging(), s1mp1e$mouseX, s1mp1e$mouseY);
        s1mp1e$restoreVanillaBlitState();
    }

    // ---- C: scroller sprite → glass slider (also computes the per-frame glide state for D) ----
    @Redirect(method = "drawBackground",
            at = @At(value = "INVOKE", ordinal = 1,
                     target = "Lnet/minecraft/client/gui/screen/ingame/StonecutterScreen;"
                            + "drawTexture(Lnet/minecraft/client/util/math/MatrixStack;IIIIII)V"))
    private void s1mp1e$glassScroller(StonecutterScreen self, MatrixStack matrices, int sx, int sy, int u, int v, int w, int h) {
        s1mp1e$sliding = false;
        s1mp1e$scrollPx = 0f;
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) {
            self.drawTexture(matrices, sx, sy, u, v, w, h);
            return;
        }
        boolean active = shouldScroll();
        HandledScreenAccessor a = s1mp1e$acc();
        float cx = a.s1mp1e$x() + 119 + 6f;       // 12-wide thumb at x+119 → centre +6
        float trackTop = a.s1mp1e$y() + 15f;
        // Point the thumb at the ROW-ALIGNED logical ratio (scrollOffset/4 of getMaxScroll), not the continuous
        // scrollAmount: after a drag scrollAmount rests between rows while vanilla hit-tests the rounded row, so easing
        // to scrollAmount would leave the grid drawn off the clickable rows. At rest thumb, grid and hit-test agree.
        int maxScroll = Math.max(0, getMaxScroll());
        int row = maxScroll <= 0 ? 0 : Math.min(maxScroll, Math.max(0, scrollOffset / GRID_COLS));
        float ratio = maxScroll <= 0 ? 0f : (float) row / maxScroll;
        // stonecutter track: 15 px thumb, top y+15, thumb-top travel 41 (vanilla y = j+15+(int)(41*scrollAmount))
        GlassScrollbar.run(s1mp1e$bar, matrices, cx, trackTop, 41f, 15f,
                ratio, active, mouseClicked && active, s1mp1e$mouseY, s1mp1e$glass.fade());
        if (active && maxScroll > 0) {
            float easedRows = s1mp1e$bar.pos() * maxScroll;
            if (Math.abs(easedRows - row) > 0.02f) {
                s1mp1e$sliding = true;
                s1mp1e$scrollPx = easedRows * CELL_H;
            }
        }
        // The glass draws leave MC's GUI shader state behind; the following renderRecipeBackground button blits rely on
        // the position_tex shader + stonecutter texture vanilla bound once at drawBackground HEAD.
        s1mp1e$restoreVanillaBlitState();
    }

    @Unique private static void s1mp1e$restoreVanillaBlitState() {
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.setShaderTexture(0, S1MP1E_TEX);
    }

    // ---- D: every recipe from row 0, translated by the eased scroll, clipped to the 3-row window ----
    @WrapOperation(method = "drawBackground",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screen/ingame/StonecutterScreen;"
                            + "renderRecipeBackground(Lnet/minecraft/client/util/math/MatrixStack;IIIII)V"))
    private void s1mp1e$slideRecipeBg(StonecutterScreen self, MatrixStack matrices, int mouseX, int mouseY,
                                      int baseX, int baseY, int endIndex, Operation<Void> op) {
        if (!s1mp1e$sliding) { op.call(self, matrices, mouseX, mouseY, baseX, baseY, endIndex); return; }
        int saved = scrollOffset;
        scrollOffset = 0;
        GuiScissor.enable(baseX, baseY, baseX + GRID_COLS * CELL_W, baseY + VIS_ROWS * CELL_H + 2);
        matrices.push();
        matrices.translate(0.0, -s1mp1e$scrollPx, 0.0);
        try {
            // no hover highlight mid-glide (vanilla would test the pointer against the UNtranslated cells)
            op.call(self, matrices, -10000, -10000, baseX, baseY, Integer.MAX_VALUE);
        } finally {
            matrices.pop();
            GuiScissor.disable();
            scrollOffset = saved;
        }
    }

    /** Same sub-pixel slide + clip for the recipe RESULT icons (drawn through the RenderSystem model-view stack). */
    @WrapOperation(method = "drawBackground",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screen/ingame/StonecutterScreen;renderRecipeIcons(III)V"))
    private void s1mp1e$slideRecipeIcons(StonecutterScreen self, int baseX, int baseY, int endIndex, Operation<Void> op) {
        if (!s1mp1e$sliding) { op.call(self, baseX, baseY, endIndex); return; }
        int saved = scrollOffset;
        scrollOffset = 0;
        GuiScissor.enable(baseX, baseY, baseX + GRID_COLS * CELL_W, baseY + VIS_ROWS * CELL_H + 2);
        MatrixStack mv = RenderSystem.getModelViewStack();
        mv.push();
        mv.translate(0.0, -s1mp1e$scrollPx, 0.0);
        RenderSystem.applyModelViewMatrix();
        try {
            op.call(self, baseX, baseY, Integer.MAX_VALUE);
        } finally {
            mv.pop();
            RenderSystem.applyModelViewMatrix();
            GuiScissor.disable();
            scrollOffset = saved;
        }
        // iOS-26 scroll-edge whisper over the moving window (fresh composite grab: blurs the recipes, never itself;
        // nothing later in this frame samples the backdrop except tooltips, which grab their own).
        if (GlassProgram.edgeUsable()) {
            SceneCapture.grabNow();
            int maxScroll = Math.max(0, getMaxScroll());
            float rows = s1mp1e$scrollPx / CELL_H;
            float topK = rows > 0.03f ? 1f : 0f;
            float botK = rows < maxScroll - 0.03f ? 1f : 0f;
            GlassWidgets.scrollEdges(baseX, baseY, baseX + GRID_COLS * CELL_W, baseY + VIS_ROWS * CELL_H + 2, 5f,
                    topK, botK, s1mp1e$glass.fade());
        }
    }

    /** No recipe tooltip mid-glide: vanilla maps the pointer to the row-aligned cells, not the gliding ones. */
    @WrapOperation(method = "drawMouseoverTooltip",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screen/ingame/StonecutterScreen;"
                            + "renderTooltip(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/item/ItemStack;II)V"))
    private void s1mp1e$recipeTooltip(StonecutterScreen self, MatrixStack matrices, ItemStack stack, int x, int y,
                                      Operation<Void> op) {
        if (!s1mp1e$sliding) op.call(self, matrices, stack, x, y);
    }

    /** A click mid-glide snaps to the target row first, so it always selects the recipe drawn under the cursor. */
    @Inject(method = "mouseClicked", at = @At("HEAD"))
    private void s1mp1e$snapOnClick(double mx, double my, int button, CallbackInfoReturnable<Boolean> cir) {
        if (s1mp1e$sliding) {
            s1mp1e$bar.snapToTarget();
            s1mp1e$sliding = false;
        }
    }

    @Override
    public void s1mp1e$endScrollDrag() { mouseClicked = false; }

    @Override
    public boolean s1mp1e$probeGliding() { return s1mp1e$sliding; }

    @Override
    public float s1mp1e$probeOffsetPx() { return s1mp1e$bar.pos() * Math.max(0, getMaxScroll()) * CELL_H; }

    @Override
    public float s1mp1e$probeLift() { return s1mp1e$bar.liftValue(); }
}
