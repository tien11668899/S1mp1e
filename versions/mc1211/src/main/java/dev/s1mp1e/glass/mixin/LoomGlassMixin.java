package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.GlassScrollbar;
import dev.s1mp1e.client.gui.GlideProbe;
import dev.s1mp1e.client.gui.ScrollDragOwner;
import dev.s1mp1e.client.gui.GlassWidgets;
import dev.s1mp1e.glass.render.ContainerGlass;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.block.entity.BannerPattern;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.LoomScreen;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.DiffuseLighting;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.block.entity.BannerBlockEntityRenderer;
import net.minecraft.client.render.model.ModelLoader;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.component.type.BannerPatternsComponent;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.screen.LoomScreenHandler;
import net.minecraft.util.DyeColor;
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

import java.util.List;

/**
 * Loom pattern list → container glass (panel + lattice + hover) + the shared vertical glass slider (C) + the silky
 * sub-pixel pattern-grid glide (D). 1.21.1 (DrawContext) analogue of 26.2's {@code LoomScrollGlassMixin}. The generic
 * container mixin routes the loom OUT of the drawBackground swallow, and this mixin replaces only the pieces: the body
 * blit (→ glass panel), the scroller thumb (→ glass slider) and — during a glide — the pattern cells.
 *
 * <h3>Seams (verified from the decompiled 1.21.1 {@code LoomScreen})</h3>
 * {@code drawBackground} draws: the body PNG ({@code drawTexture(Identifier,IIIIII)} ordinal 0), up to three 16×16 slot
 * markers ({@code drawGuiTexture(Identifier,IIII)}), the scroller thumb ({@code drawGuiTexture}, the ONLY 12×15 one, at
 * {@code x+119, y+13+(int)(41*scrollPosition)}), the output banner preview, then — when {@code canApplyDyePattern} — the
 * 4×4 pattern grid: per cell a 14×14 frame sprite ({@code drawGuiTexture}) + {@code drawBanner(ctx, pattern, x, y)} at
 * {@code (x+60+col*14, y+13+row*14)} for rows {@code visibleTopRow..+3}. {@code visibleTopRow} is the row-aligned
 * logical scroll (clicks hit-test it), {@code getRows()} the total pattern rows.
 *
 * <h3>Content glide (D) — TRUE sub-pixel</h3>
 * While the eased thumb value differs from {@code visibleTopRow}: the vanilla 14×14 frames and {@code drawBanner} calls
 * are suppressed, and at {@code drawBackground} TAIL the visible rows (+1 extra) are redrawn at their absolute rows
 * shifted up by the eased fractional offset, scissored to the 4-row window (absolute coords — drawBackground runs at
 * pose identity and 1.21.1 {@code enableScissor} ignores the pose anyway). Frames slide through a {@code translate} of
 * the context matrix; the banner is re-rendered through its OWN {@code MatrixStack} (1.21.1's {@code drawBanner} builds
 * a fresh {@code new MatrixStack()} from int x/y, independent of the context pose), translated to the SAME fractional
 * y — so frame and banner stay locked and the glide is truly sub-pixel (1.20.1 had to quantise it to 1 px). A click
 * mid-glide snaps to the target row first.
 */
@Mixin(LoomScreen.class)
public abstract class LoomGlassMixin implements GlideProbe, ScrollDragOwner {

    @Unique private static final int LG_X0 = 60, LG_Y0 = 13, LG_COLS = 4, LG_VIS = 4, LG_CELL = 14;

    @Shadow private float scrollPosition;
    @Shadow private boolean scrollbarClicked;    // the vanilla scrollbar-drag flag
    @Shadow private boolean canApplyDyePattern;  // true when the pattern list is shown/active
    @Shadow private int visibleTopRow;           // row-aligned logical scroll (hit-test row)
    @Shadow private ModelPart bannerField;
    @Shadow @Final private static Identifier PATTERN_SELECTED_TEXTURE;
    @Shadow @Final private static Identifier PATTERN_TEXTURE;
    @Shadow private int getRows() { return 0; }
    @Shadow private void drawBanner(DrawContext context, RegistryEntry<BannerPattern> pattern, int x, int y) {
        throw new AssertionError();
    }

    @Unique private final ContainerGlass.State s1mp1e$glass = new ContainerGlass.State();
    @Unique private final GlassScrollbar s1mp1e$bar = new GlassScrollbar();
    @Unique private int s1mp1e$mouseX, s1mp1e$mouseY;
    @Unique private boolean s1mp1e$sliding;
    @Unique private int s1mp1e$glideBase;
    @Unique private float s1mp1e$glideFracPx;

    @Unique private HandledScreenAccessor s1mp1e$acc() { return (HandledScreenAccessor) (Object) this; }
    @Unique private LoomScreenHandler s1mp1e$handler() {
        return (LoomScreenHandler) ((HandledScreen<?>) (Object) this).getScreenHandler();
    }

    @Inject(method = "drawBackground", at = @At("HEAD"))
    private void s1mp1e$captureMouse(DrawContext ctx, float delta, int mouseX, int mouseY, CallbackInfo ci) {
        s1mp1e$mouseX = mouseX;
        s1mp1e$mouseY = mouseY;
        s1mp1e$sliding = false;
    }

    // ---- A/B: body PNG → container glass ----
    @Redirect(method = "drawBackground",
            at = @At(value = "INVOKE", ordinal = 0,
                     target = "Lnet/minecraft/client/gui/DrawContext;drawTexture(Lnet/minecraft/util/Identifier;IIIIII)V"))
    private void s1mp1e$glassBody(DrawContext ctx, Identifier tex, int x, int y, int u, int v, int w, int h) {
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) {
            ctx.drawTexture(tex, x, y, u, v, w, h);
            return;
        }
        HandledScreenAccessor a = s1mp1e$acc();
        ContainerGlass.draw(s1mp1e$glass, ctx, x, y, w, h,
                a.s1mp1e$handler().slots, a.s1mp1e$cursorDragSlots(), a.s1mp1e$cursorDragging(),
                s1mp1e$mouseX, s1mp1e$mouseY);
    }

    // ---- C: scroller thumb → glass slider (+ D glide state); D: 14×14 pattern frames suppressed mid-glide ----
    @Redirect(method = "drawBackground",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/DrawContext;drawGuiTexture(Lnet/minecraft/util/Identifier;IIII)V"))
    private void s1mp1e$sprites(DrawContext ctx, Identifier tex, int bx, int by, int bw, int bh) {
        boolean glass = GlassProgram.ensureReady() && GlassProgram.usable();
        if (glass && bw == 12 && bh == 15) {                      // the scroller thumb (the only 12×15 sprite)
            HandledScreenAccessor a = s1mp1e$acc();
            int offscreen = Math.max(0, getRows() - LG_VIS);
            int row = MathHelper.clamp(visibleTopRow, 0, offscreen);
            float ratio = offscreen <= 0 ? 0f : (float) row / offscreen;   // row-aligned logical ratio
            float cx = a.s1mp1e$x() + 119 + 6f;
            float trackTop = a.s1mp1e$y() + 13f;
            boolean active = canApplyDyePattern && offscreen > 0;
            ctx.draw();
            GlassScrollbar.run(s1mp1e$bar, ctx, cx, trackTop, 41f, 15f,
                    ratio, active, scrollbarClicked && active, s1mp1e$mouseY, 1f);
            if (active) {
                float easedRows = s1mp1e$bar.pos() * offscreen;
                if (Math.abs(easedRows - row) > 0.02f) {
                    s1mp1e$sliding = true;
                    s1mp1e$glideBase = MathHelper.clamp((int) Math.floor(easedRows), 0, offscreen);
                    s1mp1e$glideFracPx = (easedRows - s1mp1e$glideBase) * LG_CELL;
                }
            }
            return;
        }
        if (glass && s1mp1e$sliding && bw == LG_CELL && bh == LG_CELL) return;   // pattern frame: redrawn by the overlay
        ctx.drawGuiTexture(tex, bx, by, bw, bh);
    }

    /** Suppress the vanilla banner of each pattern cell during a glide (the overlay redraws it sub-pixel). */
    @Redirect(method = "drawBackground",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screen/ingame/LoomScreen;"
                            + "drawBanner(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/registry/entry/RegistryEntry;II)V"))
    private void s1mp1e$cellBanner(LoomScreen self, DrawContext ctx, RegistryEntry<BannerPattern> pattern, int x, int y) {
        if (!s1mp1e$sliding) this.drawBanner(ctx, pattern, x, y);   // self == this; call the shadow
    }

    /** Redraw the visible patterns at their absolute rows, shifted by the eased value, clipped to the 4-row window. */
    @Inject(method = "drawBackground", at = @At("TAIL"))
    private void s1mp1e$glideOverlay(DrawContext ctx, float delta, int mouseX, int mouseY, CallbackInfo ci) {
        if (!s1mp1e$sliding || !canApplyDyePattern) return;
        LoomScreenHandler h = s1mp1e$handler();
        List<RegistryEntry<BannerPattern>> patterns = h.getBannerPatterns();
        int selected = h.getSelectedPattern();
        HandledScreenAccessor a = s1mp1e$acc();
        int x0 = a.s1mp1e$x() + LG_X0, y0 = a.s1mp1e$y() + LG_Y0;
        int x1 = x0 + LG_COLS * LG_CELL, y1 = y0 + LG_VIS * LG_CELL;
        float frac = s1mp1e$glideFracPx;

        ctx.draw();
        ctx.enableScissor(x0, y0, x1, y1);
        DiffuseLighting.disableGuiDepthLighting();   // the vanilla pattern-loop lighting state
        try {
            for (int vr = 0; vr <= LG_VIS; vr++) {   // 4 visible + ONE extra row
                int row = s1mp1e$glideBase + vr;
                int cy = y0 + vr * LG_CELL;
                for (int c = 0; c < LG_COLS; c++) {
                    int idx = row * LG_COLS + c;
                    if (idx < 0 || idx >= patterns.size()) continue;
                    int cx = x0 + c * LG_CELL;
                    ctx.getMatrices().push();
                    ctx.getMatrices().translate(0f, -frac, 0f);
                    ctx.drawGuiTexture(idx == selected ? PATTERN_SELECTED_TEXTURE : PATTERN_TEXTURE, cx, cy, LG_CELL, LG_CELL);
                    ctx.getMatrices().pop();
                    s1mp1e$banner(ctx, patterns.get(idx), cx, cy - frac);
                }
            }
        } finally {
            DiffuseLighting.enableGuiDepthLighting();
            ctx.disableScissor();
        }

        // iOS-26 scroll-edge whisper over the moving window (fresh composite grab: blurs the patterns, never itself)
        if (GlassProgram.edgeUsable()) {
            SceneCapture.grabNow();
            float topK = s1mp1e$glideBase > 0 || frac > 0.5f ? 1f : 0f;
            float botK = (s1mp1e$glideBase + LG_VIS) < getRows() ? 1f : 0f;
            GlassWidgets.scrollEdges(x0, y0, x1, y1, 5f, topK, botK, 1f);
        }
    }

    /** 1.21.1 {@code LoomScreen.drawBanner}, verbatim except the y is a FLOAT (the eased row position). */
    @Unique
    private void s1mp1e$banner(DrawContext ctx, RegistryEntry<BannerPattern> pattern, float x, float y) {
        MatrixStack ms = new MatrixStack();
        ms.push();
        ms.translate(x + 0.5f, y + 16f, 0f);
        ms.scale(6.0f, -6.0f, 1.0f);
        ms.translate(0.5f, 0.5f, 0.0f);
        ms.translate(0.5f, 0.5f, 0.5f);
        ms.scale(0.6666667f, -0.6666667f, -0.6666667f);
        this.bannerField.pitch = 0.0f;
        this.bannerField.pivotY = -32.0f;
        BannerPatternsComponent comp = new BannerPatternsComponent.Builder().add(pattern, DyeColor.WHITE).build();
        BannerBlockEntityRenderer.renderCanvas(ms, ctx.getVertexConsumers(), 15728880, OverlayTexture.DEFAULT_UV,
                this.bannerField, ModelLoader.BANNER_BASE, true, DyeColor.GRAY, comp);
        ms.pop();
        ctx.draw();
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
    public boolean s1mp1e$probeGliding() { return s1mp1e$sliding; }

    @Override
    public void s1mp1e$endScrollDrag() { scrollbarClicked = false; }

    @Override
    public float s1mp1e$probeOffsetPx() { return s1mp1e$bar.pos() * Math.max(0, getRows() - LG_VIS) * LG_CELL; }
}
