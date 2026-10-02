package dev.s1mp1e.glass.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.s1mp1e.client.gui.GlassScrollbar;
import dev.s1mp1e.client.gui.GlassWidgets;
import dev.s1mp1e.client.gui.ScrollDragOwner;
import dev.s1mp1e.glass.render.ContainerGlass;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.ContainerScreen;
import net.minecraft.client.gui.screen.ingame.StonecutterScreen;
import net.minecraft.container.StonecutterContainer;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Stonecutter recipe list -> container glass (panel + lattice + hover) + the shared vertical glass slider (C) + the
 * config-menu silky sub-pixel content glide (D). 1.15.2 ({@link MatrixStack}, legacy fixed-function GL) port of 26.2's
 * {@code StonecutterScrollGlassMixin}, structured like the verified 1.17.1 sibling.
 *
 * <h3>Why a dedicated mixin (the KNOWN BLOCKER)</h3>
 * The generic {@code HandledScreenGlassMixin} swallowed {@code ContainerScreen.render}'s whole {@code drawBackground}
 * call, so the stonecutter's recipe list, scroller and even its dim ({@code renderBackground} runs INSIDE the
 * stonecutter {@code drawBackground}) vanished. The generic mixin now routes the stonecutter OUT of the swallow; this
 * mixin replaces only the pieces: the body blit -> {@link ContainerGlass}, the scroller sprite -> the glass slider, the
 * recipe draws -> the eased sub-pixel glide.
 *
 * <h3>Seams (1.15.2 bytecode)</h3>
 * {@code drawBackground(MatrixStack,FII)}: {@code renderBackground} (dim), then {@code drawTexture(MatrixStack,IIIIII)}
 * ordinal 0 = the body at {@code (x,y)}, ordinal 1 = the scroller at {@code (x+119, y+15+(int)(41*scrollAmount), ...,
 * 12, 15)}, then {@code renderRecipeBackground(mouseX, mouseY, x+52, y+14, scrollOffset+12)} and
 * {@code renderRecipeIcons(x+52, y+14, scrollOffset+12)} (no MatrixStack — its items compose the RenderSystem global
 * model-view). The 4x3 grid is 16x18 cells at {@code (x+52, y+14+2)}; {@code scrollOffset} is the row-aligned top cell
 * index (multiple of 4), {@code getMaxScroll()} the off-screen row count, {@code shouldScroll()} = more than 12 recipes.
 *
 * <h3>Glide (D)</h3>
 * While the eased thumb value differs from the logical row, both recipe draws run with {@code scrollOffset} moved to the
 * glide base row and an unbounded end, translated up by the eased offset on the GLOBAL model-view (which in 1.15.2
 * composes onto BOTH the {@code matrices}-based button blits and the RenderSystem-matrix item icons), scissored
 * (absolute: drawBackground runs at pose identity) to the 3-row window with one extra row, and with no hover highlight.
 * A click mid-glide snaps to the target row first.
 */
@Mixin(StonecutterScreen.class)
public abstract class StonecutterScrollGlassMixin implements ScrollDragOwner {

    @Unique private static final Identifier S1MP1E_TEX = new Identifier("textures/gui/container/stonecutter.png");
    @Unique private static final int GRID_COLS = 4, CELL_W = 16, CELL_H = 18, VIS_ROWS = 3;

    @Shadow private float scrollAmount;
    @Shadow private int scrollOffset;
    @Shadow private boolean canCraft;
    @Shadow private boolean mouseClicked;       // the vanilla scrollbar-drag flag
    @Shadow protected abstract int getMaxScroll();
    @Shadow private boolean shouldScroll() { throw new AssertionError(); }
    @Shadow private void renderRecipeBackground(int mouseX, int mouseY, int x, int y, int scrollEnd) { throw new AssertionError(); }
    @Shadow private void renderRecipeIcons(int x, int y, int scrollEnd) { throw new AssertionError(); }

    @Unique private final ContainerGlass.State s1mp1e$glass = new ContainerGlass.State();
    @Unique private final GlassScrollbar s1mp1e$bar = new GlassScrollbar();
    @Unique private int s1mp1e$mouseX, s1mp1e$mouseY;
    @Unique private boolean s1mp1e$sliding;
    @Unique private int s1mp1e$glideBase;
    @Unique private float s1mp1e$glideFracPx;

    @Unique private HandledScreenAccessor s1mp1e$acc() { return (HandledScreenAccessor) (Object) this; }
    @Unique private StonecutterContainer s1mp1e$handler() {
        return (StonecutterContainer) ((ContainerScreen<?>) (Object) this).getContainer();
    }

    /** Capture the pointer for the glass slider (the scroller redirect doesn't receive it). */
    @Inject(method = "drawBackground", at = @At("HEAD"))
    private void s1mp1e$captureMouse(float delta, int mouseX, int mouseY, CallbackInfo ci) {
        s1mp1e$mouseX = mouseX;
        s1mp1e$mouseY = mouseY;
    }

    /** A/B: body PNG -> container glass (panel + lattice + hover); the recipe list still draws on top. */
    @Redirect(method = "drawBackground",
            at = @At(value = "INVOKE", ordinal = 0,
                     target = "Lnet/minecraft/client/gui/screen/ingame/StonecutterScreen;blit(IIIIII)V"))
    private void s1mp1e$glassBody(StonecutterScreen self, int x, int y, int u, int v, int w, int h) {
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) {
            self.blit(x, y, u, v, w, h);
            return;
        }
        HandledScreenAccessor a = s1mp1e$acc();
        ContainerGlass.draw(s1mp1e$glass, x, y, w, h, a.s1mp1e$handler().slots, a.s1mp1e$cursorDragSlots(),
                a.s1mp1e$cursorDragging(), s1mp1e$mouseX, s1mp1e$mouseY);
    }

    /** C: scroller sprite -> glass slider (also computes the per-frame glide state for D), then rebind the vanilla
     *  texture so the following recipe-button blits are correct. */
    @Redirect(method = "drawBackground",
            at = @At(value = "INVOKE", ordinal = 1,
                     target = "Lnet/minecraft/client/gui/screen/ingame/StonecutterScreen;blit(IIIIII)V"))
    private void s1mp1e$glassScroller(StonecutterScreen self, int sx, int sy, int u, int v, int w, int h) {
        s1mp1e$sliding = false;
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) {
            self.blit(sx, sy, u, v, w, h);
            return;
        }
        boolean active = shouldScroll();
        HandledScreenAccessor a = s1mp1e$acc();
        int maxScroll = Math.max(0, getMaxScroll());
        int row = maxScroll <= 0 ? 0 : MathHelper.clamp(scrollOffset / GRID_COLS, 0, maxScroll);
        float ratio = maxScroll <= 0 ? 0f : (float) row / maxScroll;
        // stonecutter track: 12x15 thumb at x+119, top y+15, thumb-top travel 41 (vanilla y = j+15+(int)(41*scrollAmount))
        float cx = a.s1mp1e$x() + 119 + 6f;
        float trackTop = a.s1mp1e$y() + 15f;
        GlassScrollbar.run(s1mp1e$bar, cx, trackTop, 41f, 15f, ratio, active,
                mouseClicked && active, s1mp1e$mouseY, s1mp1e$glass.fade());
        if (active && maxScroll > 0) {
            float easedRows = s1mp1e$bar.pos() * maxScroll;
            if (Math.abs(easedRows - row) > 0.02f) {
                s1mp1e$sliding = true;
                s1mp1e$glideBase = MathHelper.clamp((int) Math.floor(easedRows), 0, maxScroll);
                s1mp1e$glideFracPx = (easedRows - s1mp1e$glideBase) * CELL_H;
            }
        }
        // The glass draws left the SceneCapture texture bound; the following renderRecipeBackground button blits rely on
        // the stonecutter texture vanilla bound once at drawBackground HEAD.
        MinecraftClient.getInstance().getTextureManager().bindTexture(S1MP1E_TEX);
    }

    /** D: the recipe button backgrounds from the glide base row, translated by the eased scroll, clipped to the window. */
    @Redirect(method = "drawBackground",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screen/ingame/StonecutterScreen;renderRecipeBackground(IIIII)V"))
    private void s1mp1e$slideRecipeBg(StonecutterScreen self, int mouseX, int mouseY,
                                      int baseX, int baseY, int scrollEnd) {
        if (!s1mp1e$sliding) { this.renderRecipeBackground(mouseX, mouseY, baseX, baseY, scrollEnd); return; }
        int saved = scrollOffset;
        scrollOffset = s1mp1e$glideBase * GRID_COLS;
        GlassWidgets.beginScissor(baseX, baseY, baseX + GRID_COLS * CELL_W, baseY + VIS_ROWS * CELL_H + 2);
        RenderSystem.pushMatrix();
        RenderSystem.translatef(0f, -s1mp1e$glideFracPx, 0f);
        try {
            // no hover highlight mid-glide (vanilla would test the pointer against the UNtranslated cells)
            this.renderRecipeBackground(-10000, -10000, baseX, baseY, scrollOffset + (VIS_ROWS + 1) * GRID_COLS);
        } finally {
            RenderSystem.popMatrix();
            GlassWidgets.endScissor();
            scrollOffset = saved;
        }
    }

    /** D: the recipe RESULT icons, same sub-pixel slide + clip, then the iOS-26 scroll-edge whisper. */
    @Redirect(method = "drawBackground",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screen/ingame/StonecutterScreen;renderRecipeIcons(III)V"))
    private void s1mp1e$slideRecipeIcons(StonecutterScreen self, int baseX, int baseY, int scrollEnd) {
        if (!s1mp1e$sliding) { this.renderRecipeIcons(baseX, baseY, scrollEnd); return; }
        int saved = scrollOffset;
        scrollOffset = s1mp1e$glideBase * GRID_COLS;
        GlassWidgets.beginScissor(baseX, baseY, baseX + GRID_COLS * CELL_W, baseY + VIS_ROWS * CELL_H + 2);
        RenderSystem.pushMatrix();
        RenderSystem.translatef(0f, -s1mp1e$glideFracPx, 0f);
        try {
            this.renderRecipeIcons(baseX, baseY, scrollOffset + (VIS_ROWS + 1) * GRID_COLS);
        } finally {
            RenderSystem.popMatrix();
            GlassWidgets.endScissor();
            scrollOffset = saved;
        }
        // iOS-26 scroll-edge whisper over the moving window (fresh composite grab: blurs the recipes, never itself).
        if (GlassProgram.edgeUsable()) {
            SceneCapture.grabNow();
            int maxScroll = Math.max(0, getMaxScroll());
            float rows = s1mp1e$glideBase + s1mp1e$glideFracPx / CELL_H;
            float topK = rows > 0.03f ? 1f : 0f;
            float botK = rows < maxScroll - 0.03f ? 1f : 0f;
            GlassWidgets.scrollEdges(baseX, baseY, baseX + GRID_COLS * CELL_W, baseY + VIS_ROWS * CELL_H + 2, 5f,
                    topK, botK, s1mp1e$glass.fade());
        }
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
}
