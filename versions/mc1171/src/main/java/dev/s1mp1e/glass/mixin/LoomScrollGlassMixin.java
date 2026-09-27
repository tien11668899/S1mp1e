package dev.s1mp1e.glass.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.s1mp1e.client.gui.GlassScrollbar;
import dev.s1mp1e.client.gui.GlassWidgets;
import dev.s1mp1e.client.gui.GlideProbe;
import dev.s1mp1e.client.gui.GuiScissor;
import dev.s1mp1e.client.gui.ScrollDragOwner;
import dev.s1mp1e.glass.render.ContainerGlass;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.block.entity.BannerPattern;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.LoomScreen;
import net.minecraft.client.render.DiffuseLighting;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.screen.LoomScreenHandler;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Loom pattern list → container glass (panel + lattice + hover) + the shared vertical glass slider (C) + the silky
 * sub-pixel pattern-grid glide (D). 1.17.1 ({@link MatrixStack}, core profile) VERSION-SPECIFIC port of 26.2's
 * {@code LoomScrollGlassMixin} — 1.17.1's loom API is the legacy one (identical to 1.18.2, NOT the 1.19+/1.21 registry
 * form):
 * <ul>
 *   <li>the grid is driven by {@code firstPatternButtonId} (the top-left button id, {@code = 1 + row*4}; id 0 = the
 *       base, not shown), total rows {@code PATTERN_BUTTON_ROW_COUNT};</li>
 *   <li>each cell's preview is {@code drawBanner(int id, int x, int y)} (a global pattern id);</li>
 *   <li>the selectable ids are {@code 1 .. BannerPattern.COUNT − BannerPattern.HAS_PATTERN_ITEM_COUNT − 1}.</li>
 * </ul>
 *
 * <h3>Seams (javap-verified)</h3>
 * {@code drawBackground}: {@code renderBackground} (dim), the body {@code drawTexture(MatrixStack,IIIIII)} at
 * {@code (x,y,0,0,w,h)}, three 16×16 empty-slot markers, the 12×15 scroller at {@code (x+119,
 * y+13+(int)(41*scrollPosition), 232|244, 0, 12, 15)}, the output banner preview, then — when
 * {@code canApplyDyePattern} — per cell a 14×14 frame {@code drawTexture} + {@code drawBanner(id, x, y)} at
 * {@code (x+60+col*14, y+13+row*14)}.
 *
 * <h3>Content glide (D) — TRUE sub-pixel</h3>
 * 1.17.1's {@code drawBanner} builds its own {@code MatrixStack} and flushes the banner model through
 * {@code Immediate.draw()}, which composes the current RenderSystem model-view; the frame sprite is a
 * {@code drawTexture} that also composes it. So ONE model-view translate moves frame + banner by exactly the same
 * fractional offset. While the eased thumb value differs from the logical row: the vanilla loop's 14×14 frames and
 * banners are suppressed and, at {@code drawBackground} TAIL, the visible rows (+1 extra) are redrawn at their absolute
 * rows under the eased model-view offset, scissored (absolute — drawBackground runs at pose identity) to the 4-row
 * window, plus the scroll-edge whisper. A mid-glide click snaps to the target row first.
 */
@Mixin(LoomScreen.class)
public abstract class LoomScrollGlassMixin implements GlideProbe, ScrollDragOwner {

    @Unique private static final Identifier S1MP1E_TEX = new Identifier("textures/gui/container/loom.png");
    @Unique private static final int LG_X0 = 60, LG_Y0 = 13, LG_COLS = 4, LG_VIS = 4, LG_CELL = 14;

    @Shadow private boolean scrollbarClicked;    // the vanilla scrollbar-drag flag
    @Shadow private boolean canApplyDyePattern;  // true when the pattern list is shown / active
    @Shadow private int firstPatternButtonId;    // row-aligned logical scroll (1 + row*4)
    @Shadow @Final private static int PATTERN_BUTTON_ROW_COUNT;
    @Shadow protected abstract void drawBanner(int pattern, int x, int y);

    @Unique private final ContainerGlass.State s1mp1e$glass = new ContainerGlass.State();
    @Unique private final GlassScrollbar s1mp1e$bar = new GlassScrollbar();
    @Unique private int s1mp1e$mouseX, s1mp1e$mouseY;
    @Unique private boolean s1mp1e$sliding;
    @Unique private int s1mp1e$glideBaseRow;
    @Unique private float s1mp1e$glideFracPx;

    @Unique private HandledScreenAccessor s1mp1e$acc() { return (HandledScreenAccessor) (Object) this; }
    @Unique private LoomScreenHandler s1mp1e$handler() {
        return (LoomScreenHandler) ((HandledScreen<?>) (Object) this).getScreenHandler();
    }
    /** Highest selectable pattern id (exclusive) — vanilla's grid-loop break condition. */
    @Unique private static int s1mp1e$patLimit() { return BannerPattern.COUNT - BannerPattern.HAS_PATTERN_ITEM_COUNT; }
    @Unique private static int s1mp1e$offscreen() { return Math.max(0, PATTERN_BUTTON_ROW_COUNT - LG_VIS); }

    @Inject(method = "drawBackground", at = @At("HEAD"))
    private void s1mp1e$captureMouse(MatrixStack matrices, float delta, int mouseX, int mouseY, CallbackInfo ci) {
        s1mp1e$mouseX = mouseX;
        s1mp1e$mouseY = mouseY;
        s1mp1e$sliding = false;
    }

    /** Body PNG → container glass; scroller sprite → glass slider + glide state; 14×14 pattern frames suppressed
     *  during a glide; every other loom blit (slot markers, the special-pattern cell) stays vanilla. */
    @Redirect(method = "drawBackground",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screen/ingame/LoomScreen;"
                            + "drawTexture(Lnet/minecraft/client/util/math/MatrixStack;IIIIII)V"))
    private void s1mp1e$blit(LoomScreen self, MatrixStack matrices, int x, int y, int u, int v, int w, int h) {
        boolean glass = GlassProgram.ensureReady() && GlassProgram.usable();
        if (glass) {
            HandledScreenAccessor a = s1mp1e$acc();
            int px = a.s1mp1e$x(), py = a.s1mp1e$y();
            if (x == px && y == py && u == 0 && v == 0) {                 // body PNG -> container glass
                ContainerGlass.draw(s1mp1e$glass, x, y, w, h, a.s1mp1e$handler().slots, a.s1mp1e$cursorDragSlots(),
                        a.s1mp1e$cursorDragging(), s1mp1e$mouseX, s1mp1e$mouseY);
                s1mp1e$restoreVanillaBlitState();
                return;
            }
            if (w == 12 && h == 15) {                                     // the scroller (the only 12x15 blit)
                int offscreen = s1mp1e$offscreen();
                int row = MathHelper.clamp((firstPatternButtonId - 1) / LG_COLS, 0, offscreen);
                float ratio = offscreen <= 0 ? 0f : (float) row / offscreen;   // row-aligned logical ratio
                boolean active = canApplyDyePattern && offscreen > 0;
                // loom track: thumb 12x15 at x+119, top y+13, thumb-top travel 41
                GlassScrollbar.run(s1mp1e$bar, matrices, px + 119 + 6f, py + 13f, 41f, 15f,
                        ratio, active, scrollbarClicked && active, s1mp1e$mouseY, s1mp1e$glass.fade());
                if (active) {
                    float easedRows = s1mp1e$bar.pos() * offscreen;
                    if (Math.abs(easedRows - row) > 0.02f) {
                        s1mp1e$sliding = true;
                        s1mp1e$glideBaseRow = MathHelper.clamp((int) Math.floor(easedRows), 0, offscreen);
                        s1mp1e$glideFracPx = (easedRows - s1mp1e$glideBaseRow) * LG_CELL;
                    }
                }
                s1mp1e$restoreVanillaBlitState();
                return;
            }
            if (w == LG_CELL && h == LG_CELL && s1mp1e$sliding) return;  // pattern frame: redrawn by the overlay
        }
        self.drawTexture(matrices, x, y, u, v, w, h);
    }

    /** Suppress the vanilla banner of each pattern cell during a glide (the overlay redraws it sub-pixel). */
    @Redirect(method = "drawBackground",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screen/ingame/LoomScreen;drawBanner(III)V"))
    private void s1mp1e$cellBanner(LoomScreen self, int id, int x, int y) {
        if (!s1mp1e$sliding) this.drawBanner(id, x, y);   // self == this; call the shadow
    }

    /** Redraw the visible patterns at their absolute rows, offset sub-pixel by the eased value (one model-view translate
     *  moves BOTH the frame sprite and its banner model), clipped to the 4-row window. */
    @Inject(method = "drawBackground", at = @At("TAIL"))
    private void s1mp1e$glideOverlay(MatrixStack matrices, float delta, int mouseX, int mouseY, CallbackInfo ci) {
        if (!s1mp1e$sliding || !canApplyDyePattern) return;
        int selected = s1mp1e$handler().getSelectedPattern();
        int limit = s1mp1e$patLimit();
        HandledScreenAccessor a = s1mp1e$acc();
        int bgH = a.s1mp1e$backgroundHeight();
        int x0 = a.s1mp1e$x() + LG_X0, y0 = a.s1mp1e$y() + LG_Y0;
        int x1 = x0 + LG_COLS * LG_CELL, y1 = y0 + LG_VIS * LG_CELL;
        LoomScreen self = (LoomScreen) (Object) this;

        GuiScissor.enable(x0, y0, x1, y1);
        DiffuseLighting.disableGuiDepthLighting();               // the vanilla pattern-loop lighting state
        MatrixStack mv = RenderSystem.getModelViewStack();
        mv.push();
        mv.translate(0.0, -s1mp1e$glideFracPx, 0.0);            // fractional glide: frame sprite + banner share it
        RenderSystem.applyModelViewMatrix();
        try {
            for (int vr = 0; vr <= LG_VIS; vr++) {               // 4 visible + ONE extra row (no edge gap)
                int row = s1mp1e$glideBaseRow + vr;
                int cy = y0 + vr * LG_CELL;                      // absolute; the model-view supplies -fracPx
                for (int c = 0; c < LG_COLS; c++) {
                    int id = 1 + row * LG_COLS + c;              // vanilla grid ids start at 1 (0 = base, not shown)
                    if (id >= limit) continue;
                    int cx = x0 + c * LG_CELL;
                    s1mp1e$restoreVanillaBlitState();            // drawBanner leaves another shader bound: rebind
                    self.drawTexture(matrices, cx, cy, 0, bgH + (id == selected ? LG_CELL : 0), LG_CELL, LG_CELL);
                    this.drawBanner(id, cx, cy);
                }
            }
        } finally {
            mv.pop();
            RenderSystem.applyModelViewMatrix();
            DiffuseLighting.enableGuiDepthLighting();
            GuiScissor.disable();
        }

        // iOS-26 scroll-edge whisper over the moving window (fresh composite grab: blurs the patterns, never itself)
        if (GlassProgram.edgeUsable()) {
            SceneCapture.grabNow();
            float topK = s1mp1e$glideBaseRow > 0 || s1mp1e$glideFracPx > 0.5f ? 1f : 0f;
            float botK = (s1mp1e$glideBaseRow + LG_VIS) < PATTERN_BUTTON_ROW_COUNT ? 1f : 0f;
            GlassWidgets.scrollEdges(x0, y0, x1, y1, 5f, topK, botK, s1mp1e$glass.fade());
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

    @Unique private static void s1mp1e$restoreVanillaBlitState() {
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.setShaderTexture(0, S1MP1E_TEX);
    }

    @Override
    public void s1mp1e$endScrollDrag() { scrollbarClicked = false; }

    @Override
    public boolean s1mp1e$probeGliding() { return s1mp1e$sliding; }

    @Override
    public float s1mp1e$probeOffsetPx() { return s1mp1e$bar.pos() * s1mp1e$offscreen() * LG_CELL; }

    @Override
    public float s1mp1e$probeLift() { return s1mp1e$bar.liftValue(); }
}
