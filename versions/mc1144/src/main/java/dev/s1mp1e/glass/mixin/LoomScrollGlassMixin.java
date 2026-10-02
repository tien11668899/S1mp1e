package dev.s1mp1e.glass.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.s1mp1e.client.gui.GlassScrollbar;
import dev.s1mp1e.client.gui.GlassWidgets;
import dev.s1mp1e.client.gui.ScrollDragOwner;
import dev.s1mp1e.glass.render.ContainerGlass;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.ContainerScreen;
import net.minecraft.client.gui.screen.ingame.LoomScreen;
import net.minecraft.container.LoomContainer;
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
 * Loom pattern list -> container glass (panel + lattice + hover) + the shared vertical glass slider (C) + silky
 * sub-pixel row glide (D). 1.14.4 ({@link MatrixStack}, legacy fixed-function GL) port of 26.2's
 * {@code LoomScrollGlassMixin}.
 *
 * <p>The pattern grid is re-enabled by the shared delegation ({@code HandledScreenGlassMixin} runs the loom's own
 * {@code drawBackground}); here the dim + body PNG are replaced by the glass panel (over the dim), the scroller sprite
 * becomes the glass scrollbar, and the empty-slot art + banner preview stay vanilla on the panel.
 *
 * <p><b>Silky content (D).</b> Vanilla snaps the 4x4 banner-pattern grid by whole rows ({@code firstPatternButtonId}).
 * Here the grid GLIDES with the SAME eased value the glass thumb uses. Vanilla keeps a row-aligned logical scroll
 * ({@code firstPatternButtonId}) so pattern clicks stay correct; while the eased offset differs from that row this mixin
 * suppresses the vanilla loop's own 14x14 pattern sprite + its per-cell banner preview ({@code method_22692}) and, at
 * the tail of {@code drawBackground}, redraws the visible patterns itself at their absolute rows translated by the
 * offset, clipped to the 4-row window with one extra row.
 *
 * <p><b>Per-family adaptation (banner uses its OWN MatrixStack).</b> {@code method_22692} builds a fresh {@code new
 * MatrixStack()} from its {@code int x,y} args, so a MatrixStack translate would not reach it — but on 1.14.4's
 * fixed-function pipeline every vertex (the MatrixStack blit of the 14x14 frame AND the banner model flushed through the
 * entity {@code Immediate}) still goes through the GL MODEL-VIEW. The overlay therefore translates the GLOBAL
 * {@code GlStateManager} matrix by {@code -fracPx}: frame + banner glide together SUB-PIXEL with the same eased value the
 * glass thumb uses (the window-px scissor is not transformed by the model-view, so it clips the fixed window).
 *
 * <p><b>1.14.4.</b> The loom has no banner model previews yet (1.15+): each pattern cell is the 14 x 14 frame from the
 * loom sheet plus a 5 x 10 blit of a cached banner texture ({@code patternButtonTextureIds[i]}, static
 * {@code blit(IIIIFFIIII)}). During a glide both are dropped from the vanilla loop and redrawn by the overlay at the
 * eased offset; the dye-able pattern count is the texture array's length minus the five item-bound patterns.
 */
@Mixin(LoomScreen.class)
public abstract class LoomScrollGlassMixin implements ScrollDragOwner {

    @Unique private static final Identifier S1MP1E_TEX = new Identifier("textures/gui/container/loom.png");
    @Unique private static final int GRID_X0 = 60, GRID_Y0 = 13, COLS = 4, VIS = 4, CELL = 14;

    @Shadow private float scrollPosition;
    @Shadow private boolean scrollbarClicked;
    @Shadow private boolean canApplyDyePattern;
    @Shadow private int firstPatternButtonId;
    /** 1.14.4: the pattern previews are cached banner textures (one per pattern), blitted 5 x 10 into each cell. */
    @Shadow @org.spongepowered.asm.mixin.Final private Identifier[] patternButtonTextureIds;
    @Shadow private static int PATTERN_BUTTON_ROW_COUNT;

    @Unique private final ContainerGlass.State s1mp1e$glass = new ContainerGlass.State();
    @Unique private final GlassScrollbar s1mp1e$bar = new GlassScrollbar();
    @Unique private int s1mp1e$mouseX, s1mp1e$mouseY;
    @Unique private boolean s1mp1e$sliding;
    @Unique private int s1mp1e$glideBase;
    @Unique private float s1mp1e$glideFracPx;

    @Unique private HandledScreenAccessor s1mp1e$acc() { return (HandledScreenAccessor) (Object) this; }
    @Unique private LoomContainer s1mp1e$handler() {
        return (LoomContainer) ((ContainerScreen<?>) (Object) this).getContainer();
    }
    /** Dye-able patterns: the texture array minus the five item-bound ones at its end (the bound of vanilla's loop). */
    @Unique private int s1mp1e$patternCount() { return this.patternButtonTextureIds.length - 5; }
    @Unique private int s1mp1e$offscreen() { return Math.max(0, PATTERN_BUTTON_ROW_COUNT - VIS); }

    @Inject(method = "drawBackground", at = @At("HEAD"))
    private void s1mp1e$captureMouse(float delta, int mouseX, int mouseY, CallbackInfo ci) {
        s1mp1e$mouseX = mouseX;
        s1mp1e$mouseY = mouseY;
        s1mp1e$sliding = false;
    }

    /** Body PNG -> glass panel; scroller sprite -> glass scrollbar; 14x14 pattern cells suppressed during a glide;
     *  empty-slot art + too-many-patterns art pass through. */
    @Redirect(method = "drawBackground",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screen/ingame/LoomScreen;blit(IIIIII)V"))
    private void s1mp1e$blit(LoomScreen self, int x, int y, int u, int v, int w, int h) {
        boolean glass = GlassProgram.ensureReady() && GlassProgram.usable();
        if (glass) {
            HandledScreenAccessor a = s1mp1e$acc();
            int px = a.s1mp1e$x(), py = a.s1mp1e$y();
            if (x == px && y == py && u == 0 && v == 0) {         // body PNG -> glass panel + slot layer
                ContainerGlass.draw(s1mp1e$glass, px, py, a.s1mp1e$backgroundWidth(), a.s1mp1e$backgroundHeight(),
                        a.s1mp1e$handler().slots, a.s1mp1e$cursorDragSlots(), a.s1mp1e$cursorDragging(),
                        s1mp1e$mouseX, s1mp1e$mouseY);
                MinecraftClient.getInstance().getTextureManager().bindTexture(S1MP1E_TEX);   // restore for the slot art
                return;
            }
            if (w == 12 && h == 15) {                             // the scroller sprite -> glass slider + glide state
                boolean active = canApplyDyePattern && s1mp1e$offscreen() > 0;
                int row = MathHelper.clamp((firstPatternButtonId - 1) / COLS, 0, s1mp1e$offscreen());
                float ratio = s1mp1e$offscreen() <= 0 ? 0f : (float) row / s1mp1e$offscreen();
                // loom track: 12x15 thumb at x+119, top y+13, thumb-top travel 41 (vanilla y = j+13+(int)(41*scrollPosition))
                GlassScrollbar.run(s1mp1e$bar, px + 119 + 6f, py + 13f, 41f, 15f, ratio, active,
                        scrollbarClicked && active, s1mp1e$mouseY, s1mp1e$glass.fade());
                if (active) {
                    float easedRows = s1mp1e$bar.pos() * s1mp1e$offscreen();
                    if (Math.abs(easedRows - row) > 0.02f) {
                        s1mp1e$sliding = true;
                        s1mp1e$glideBase = MathHelper.clamp((int) Math.floor(easedRows), 0, s1mp1e$offscreen());
                        s1mp1e$glideFracPx = (easedRows - s1mp1e$glideBase) * CELL;
                    }
                }
                MinecraftClient.getInstance().getTextureManager().bindTexture(S1MP1E_TEX);   // restore for the pattern cells
                return;
            }
            if (w == 14 && h == 14) {                             // pattern cell frame -> suppressed during a glide
                if (!s1mp1e$sliding) self.blit(x, y, u, v, w, h);
                return;
            }
        }
        self.blit(x, y, u, v, w, h);
    }

    /**
     * 1.14.4: a cell's pattern preview is the second static {@code blit(IIIIFFIIII)} of {@code drawBackground} (the
     * first is the output banner, the third the single special-pattern cell). While the grid glides, the vanilla
     * (un-offset) previews are dropped like the cell frames; the overlay below redraws both at the eased offset.
     */
    @Redirect(method = "drawBackground",
            at = @At(value = "INVOKE", ordinal = 1,
                     target = "Lnet/minecraft/client/gui/screen/ingame/LoomScreen;blit(IIIIFFIIII)V"))
    private void s1mp1e$loomPattern(int x, int y, int w, int h, float u, float v, int rw, int rh, int tw, int th) {
        if (!s1mp1e$sliding) net.minecraft.client.gui.DrawableHelper.blit(x, y, w, h, u, v, rw, rh, tw, th);
    }

    @Inject(method = "drawBackground", at = @At("TAIL"))
    private void s1mp1e$loomOverlay(float delta, int mouseX, int mouseY, CallbackInfo ci) {
        if (!s1mp1e$sliding) return;
        HandledScreenAccessor a = s1mp1e$acc();
        int px = a.s1mp1e$x(), py = a.s1mp1e$y(), bgH = a.s1mp1e$backgroundHeight();
        int x0 = px + GRID_X0, y0 = py + GRID_Y0;
        int selected = s1mp1e$handler().getSelectedPattern();
        int max = s1mp1e$patternCount();

        // SUB-PIXEL: translate the GLOBAL (GL model-view) matrix by -fracPx. On 1.14.4's fixed-function pipeline the
        // model-view composes onto BOTH the MatrixStack blit of the 14x14 frame AND the banner preview, whose
        // method_22692 builds its own identity MatrixStack but is still drawn through the GL model-view — so frame and
        // banner slide together by the same fractional offset (the scissor is in window px, unaffected by it).
        GlassWidgets.beginScissor(x0, y0, x0 + COLS * CELL, y0 + VIS * CELL);
        MinecraftClient.getInstance().getTextureManager().bindTexture(S1MP1E_TEX);
        GlStateManager.pushMatrix();
        GlStateManager.translatef(0f, -s1mp1e$glideFracPx, 0f);
        try {
            for (int gr = 0; gr <= VIS; gr++) {
                int cy = y0 + gr * CELL;
                for (int c = 0; c < COLS; c++) {
                    int o = 1 + (s1mp1e$glideBase + gr) * COLS + c;
                    if (o < 1 || o >= max) continue;
                    int cx = x0 + c * CELL;
                    int s = bgH + (o == selected ? CELL : 0);
                    // method_22692 binds the banner/entity textures, so rebind the loom sheet before EVERY cell frame
                    // (exactly as vanilla's own loop does) — else only the first frame of the redraw is visible.
                    MinecraftClient.getInstance().getTextureManager().bindTexture(S1MP1E_TEX);
                    ((LoomScreen) (Object) this).blit(cx, cy, 0, s, CELL, CELL);
                    Identifier pat = o < this.patternButtonTextureIds.length ? this.patternButtonTextureIds[o] : null;
                    if (pat != null) {                    // what vanilla's loop draws into the cell (javap-read)
                        MinecraftClient.getInstance().getTextureManager().bindTexture(pat);
                        net.minecraft.client.gui.DrawableHelper.blit(cx + 4, cy + 2, 5, 10, 1.0F, 1.0F, 20, 40, 64, 64);
                    }
                }
            }
        } finally {
            GlStateManager.popMatrix();
            GlassWidgets.endScissor();
        }

        if (GlassProgram.edgeUsable()) {
            SceneCapture.grabNow();
            float rows = s1mp1e$glideBase + s1mp1e$glideFracPx / CELL;
            float topK = rows > 0.03f ? 1f : 0f;
            float botK = rows < s1mp1e$offscreen() - 0.03f ? 1f : 0f;
            GlassWidgets.scrollEdges(x0, y0, x0 + COLS * CELL, y0 + VIS * CELL, 5f, topK, botK, s1mp1e$glass.fade());
        }
    }

    /** A click mid-glide snaps to the target row first, so it always selects the pattern drawn under the cursor. */
    @Inject(method = "mouseClicked", at = @At("HEAD"))
    private void s1mp1e$snapOnClick(double mx, double my, int button, CallbackInfoReturnable<Boolean> cir) {
        if (s1mp1e$sliding) {
            s1mp1e$bar.snapToTarget();
            s1mp1e$sliding = false;
        }
    }

    @Override
    public void s1mp1e$endScrollDrag() { scrollbarClicked = false; }
}
