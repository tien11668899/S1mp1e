package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.GlassScrollbar;
import dev.s1mp1e.client.gui.GlassWidgets;
import dev.s1mp1e.glass.anim.Fade;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.PanelGhost;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.block.entity.BannerPattern;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.LoomScreen;
import net.minecraft.client.render.DiffuseLighting;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.screen.LoomScreenHandler;
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

import java.util.List;

/**
 * Loom pattern list → the shared vertical glass slider (C) + silky sub-pixel row glide (D).
 *
 * <p>The pattern grid is re-enabled by the shared delegation (see {@code HandledScreenGlassMixin}, which runs the loom's
 * own {@code drawBackground}); here the dim + body PNG are suppressed (the glass panel replaced them) and the scroller
 * sprite becomes the glass scrollbar. Empty-slot art, banner preview and pattern cells stay vanilla and draw on the panel.
 *
 * <p><b>Silky content (D).</b> Vanilla snaps the 4x4 banner-pattern grid by whole rows (top row = {@code visibleTopRow}).
 * Here the grid GLIDES with the SAME eased value the glass thumb uses. Vanilla keeps a row-aligned logical scroll
 * ({@code visibleTopRow}) so pattern clicks stay correct; while the eased offset differs from that row this mixin
 * suppresses the vanilla loop's own 14x14 pattern sprite + its {@code drawBanner} preview and, at the tail of
 * {@code drawBackground}, redraws the visible patterns itself at their absolute rows translated by the offset, clipped to
 * the 4-row window with one extra row so no edge gap shows. A click mid-glide snaps to the target row first.
 *
 * <p><b>Per-family adaptation (banner uses its OWN matrix).</b> Unlike 26.2's retained {@code extractBannerOnButton}
 * (drawn through the extractor pose, so a {@code translate} slid it sub-pixel), 1.20.1's {@code LoomScreen.drawBanner}
 * builds a fresh {@code new MatrixStack()} from its {@code int x,y} args and renders the banner model into
 * {@code context.getVertexConsumers()} independently of {@code context.getMatrices()}. A context-matrix translate would
 * therefore move the 14x14 frame sprite but NOT its banner. To keep the frame and its banner locked together we offset
 * BOTH by the same integer {@code round(fracPx)} (via the int coords), so the glide is 1px-quantized on the loom rather
 * than truly sub-pixel — the glass thumb itself still eases sub-pixel. This is the honest 1.20.1 seam for this feature.
 */
@Mixin(LoomScreen.class)
public abstract class LoomScrollGlassMixin {

    @Unique private static final Identifier S1MP1E_LOOM_TEX = new Identifier("textures/gui/container/loom.png");
    @Unique private static final int LG_PX0 = 60, LG_PY0 = 13, LG_COLS = 4, LG_VIS = 4, LG_CELL = 14;

    @Shadow private float scrollPosition;
    @Shadow private boolean scrollbarClicked;
    @Shadow private boolean canApplyDyePattern;
    @Shadow private int visibleTopRow;
    @Shadow private int getRows() { return 0; }
    @Shadow private void drawBanner(DrawContext context, RegistryEntry<BannerPattern> pattern, int x, int y) { throw new AssertionError(); }

    @Unique private GlassScrollbar s1mp1e$scrollbar;
    @Unique private Fade s1mp1e$openFade;
    @Unique private boolean s1mp1e$opened;
    @Unique private boolean s1mp1e$sliding;
    @Unique private int s1mp1e$glideBase;
    @Unique private float s1mp1e$glideFracPx;

    @Unique private int s1mp1e$px() { return ((HandledScreenAccessor) (Object) this).s1mp1e$x(); }
    @Unique private int s1mp1e$py() { return ((HandledScreenAccessor) (Object) this).s1mp1e$y(); }
    @Unique private int s1mp1e$bgH() { return ((HandledScreenAccessor) (Object) this).s1mp1e$backgroundHeight(); }
    @Unique private LoomScreenHandler s1mp1e$handler() {
        return (LoomScreenHandler) ((HandledScreen<?>) (Object) this).getScreenHandler();
    }

    /** Body PNG → glass panel (over the vanilla dim); scroller sprite → glass scrollbar; 14x14 pattern cells suppressed
     *  during a glide; everything else vanilla. */
    @Redirect(method = "drawBackground",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/DrawContext;drawTexture(Lnet/minecraft/util/Identifier;IIIIII)V"))
    private void s1mp1e$blit(DrawContext self, Identifier tex, int x, int y, int u, int v, int w, int h,
                            DrawContext ctxEnc, float delta, int mouseX, int mouseY) {
        boolean glass = GlassProgram.ensureReady() && GlassProgram.usable();
        if (glass) {
            int px = s1mp1e$px(), py = s1mp1e$py();
            if (x == px && y == py && u == 0 && v == 0) {         // body PNG -> glass panel
                if (s1mp1e$openFade == null) s1mp1e$openFade = new Fade(0f, PanelGhost.FADE_MS);
                SceneCapture.grabNow();
                if (!s1mp1e$opened) {
                    s1mp1e$opened = true;
                    s1mp1e$openFade.snap(0f);
                    s1mp1e$openFade.to(1f);
                    PanelGhost.cancel();
                }
                float fade = s1mp1e$openFade.value();
                PanelGhost.beginFrame();
                PanelGhost.remember(x, y, w, h);
                GlassRenderer.panel(x, y, x + w, y + h, fade);
                return;
            }
            if (w == 12 && h == 15) {                             // the scroller sprite -> glass slider + glide state
                s1mp1e$sliding = false;
                if (s1mp1e$scrollbar == null) s1mp1e$scrollbar = new GlassScrollbar();
                int offscreen = Math.max(0, getRows() - LG_VIS);
                int row = MathHelper.clamp(visibleTopRow, 0, offscreen);
                float ratio = offscreen <= 0 ? 0f : (float) row / offscreen;
                // loom track: thumb 12x15, top at topPos+13, thumb-top travel 41 (vanilla y = j+13+(int)(41*scrollPosition))
                GlassScrollbar.run(s1mp1e$scrollbar, ctxEnc, x + w / 2f, py + 13f, 41f, 15f,
                        ratio, canApplyDyePattern, scrollbarClicked && canApplyDyePattern, mouseY, s1mp1e$fade());
                if (canApplyDyePattern && offscreen > 0) {
                    float easedRows = s1mp1e$scrollbar.pos() * offscreen;
                    if (Math.abs(easedRows - row) > 0.02f) {
                        s1mp1e$sliding = true;
                        s1mp1e$glideBase = MathHelper.clamp((int) Math.floor(easedRows), 0, offscreen);
                        s1mp1e$glideFracPx = (easedRows - s1mp1e$glideBase) * LG_CELL;
                    }
                }
                return;
            }
            if (w == 14 && h == 14) {                             // pattern cell frame — suppressed during a glide
                if (!s1mp1e$sliding) self.drawTexture(tex, x, y, u, v, w, h);
                return;
            }
        }
        self.drawTexture(tex, x, y, u, v, w, h);
    }

    /** Suppress the vanilla banner preview on each pattern cell during a glide (redrawn by the overlay). */
    @Redirect(method = "drawBackground",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screen/ingame/LoomScreen;"
                            + "drawBanner(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/registry/entry/RegistryEntry;II)V"))
    private void s1mp1e$loomBanner(LoomScreen self, DrawContext ctx, RegistryEntry<BannerPattern> pattern, int x, int y) {
        if (!s1mp1e$sliding) this.drawBanner(ctx, pattern, x, y);   // self == this; call the shadow
    }

    /** Redraw the visible patterns at their absolute rows, offset by the eased value, clipped to the 4-row window. */
    @Inject(method = "drawBackground", at = @At("TAIL"))
    private void s1mp1e$loomOverlay(DrawContext ctx, float delta, int mouseX, int mouseY, CallbackInfo ci) {
        if (!s1mp1e$sliding) return;
        LoomScreenHandler h = s1mp1e$handler();
        List<RegistryEntry<BannerPattern>> patterns = h.getBannerPatterns();
        int selected = h.getSelectedPattern();
        int px = s1mp1e$px(), py = s1mp1e$py(), bgH = s1mp1e$bgH();
        int x0 = px + LG_PX0, y0 = py + LG_PY0;
        int off = Math.round(s1mp1e$glideFracPx);   // 1px-quantized: frame sprite + banner share the same integer offset

        ctx.enableScissor(x0, y0, x0 + LG_COLS * LG_CELL, y0 + LG_VIS * LG_CELL);
        DiffuseLighting.disableGuiDepthLighting();   // match the vanilla pattern-loop lighting state
        try {
            for (int vr = 0; vr <= LG_VIS; vr++) {
                int row = s1mp1e$glideBase + vr;
                int cy = y0 + vr * LG_CELL - off;
                for (int c = 0; c < LG_COLS; c++) {
                    int idx = row * LG_COLS + c;
                    if (idx < 0 || idx >= patterns.size()) continue;
                    int cx = x0 + c * LG_CELL;
                    ctx.drawTexture(S1MP1E_LOOM_TEX, cx, cy, 0, idx == selected ? bgH + 14 : bgH, LG_CELL, LG_CELL);
                    this.drawBanner(ctx, patterns.get(idx), cx, cy);
                }
            }
        } finally {
            DiffuseLighting.enableGuiDepthLighting();
            ctx.disableScissor();
        }

        int offscreen = Math.max(0, getRows() - LG_VIS);
        float topK = s1mp1e$glideBase > 0 || s1mp1e$glideFracPx > 0.5f ? 1f : 0f;
        float botK = (s1mp1e$glideBase + LG_VIS) < getRows() ? 1f : 0f;
        GlassWidgets.scrollEdges(x0, y0, x0 + LG_COLS * LG_CELL, y0 + LG_VIS * LG_CELL, 5f, topK, botK, s1mp1e$fade());
        if (offscreen == 0) s1mp1e$sliding = false;
    }

    /** A click mid-glide snaps to the target row first, so it always selects the pattern drawn under the cursor. */
    @Inject(method = "mouseClicked", at = @At("HEAD"))
    private void s1mp1e$snapOnClick(double mx, double my, int button, CallbackInfoReturnable<Boolean> cir) {
        if (s1mp1e$sliding && s1mp1e$scrollbar != null) {
            s1mp1e$scrollbar.snapToTarget();
            s1mp1e$sliding = false;
        }
    }

    @Unique private float s1mp1e$fade() { return s1mp1e$openFade == null ? 1f : s1mp1e$openFade.value(); }
}
