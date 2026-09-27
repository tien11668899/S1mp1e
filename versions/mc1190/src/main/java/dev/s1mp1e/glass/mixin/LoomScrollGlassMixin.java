package dev.s1mp1e.glass.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.s1mp1e.client.gui.GlassScrollbar;
import dev.s1mp1e.client.gui.GlassWidgets;
import dev.s1mp1e.glass.anim.Fade;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.PanelGhost;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.block.entity.BannerPattern;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.LoomScreen;
import net.minecraft.client.render.DiffuseLighting;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.screen.LoomScreenHandler;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.registry.RegistryEntry;
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
 * Loom pattern list → the shared vertical glass slider (C) + silky sub-pixel row glide (D). 1.19.2
 * ({@link MatrixStack} core-profile) port of the mc1201 mixin; only the render family differs.
 *
 * <p>The pattern grid is re-enabled by the shared delegation ({@code HandledScreenGlassMixin} runs the concrete
 * {@code drawBackground} for a {@link LoomScreen} rather than swallowing it); here the body PNG becomes the glass
 * panel and the scroller sprite becomes the glass slider. The empty-slot art, banner preview and pattern cells stay
 * vanilla and draw on the panel. Because the glass draws unbind MC's {@code position_tex} shader, the vanilla shader +
 * white colour + loom texture are restored after each glass draw so the following vanilla slot / pattern blits render.
 *
 * <p><b>Silky content (D) — TRUE sub-pixel on 1.19.2.</b> Vanilla snaps the 4x4 banner-pattern grid by whole rows
 * ({@code visibleTopRow}). Here the grid GLIDES with the SAME eased value the glass thumb uses. The 1.19.2
 * {@code drawBanner} builds a fresh {@code new MatrixStack()} and flushes its banner model through an
 * {@code Immediate.draw()} — which composes the current GL model-view — so, unlike the mc1201 sibling (which could only
 * offset the banner by whole integer px because its {@code DrawContext} banner drew on the context's own vertex
 * consumers), a translate of the RenderSystem model-view stack moves BOTH the 14x14 frame sprite (a
 * {@code drawTexture} that also composes the model-view) AND its banner model by the same fractional offset. So here
 * the loom glides truly sub-pixel: vanilla keeps its row-aligned logical scroll ({@code visibleTopRow}) so pattern
 * clicks stay correct, this mixin suppresses the vanilla loop's own 14x14 frame + {@code drawBanner}, and at the tail
 * of {@code drawBackground} it redraws the visible patterns (+1 extra row) at their absolute rows under a model-view
 * translated by the eased fractional offset, clipped to the 4-row window. A mid-glide click snaps to the target first.
 */
@Mixin(LoomScreen.class)
public abstract class LoomScrollGlassMixin {

    @Unique private static final Identifier S1MP1E_TEX = new Identifier("textures/gui/container/loom.png");
    @Unique private static final int LG_PX0 = 60, LG_PY0 = 13, LG_COLS = 4, LG_VIS = 4, LG_CELL = 14;

    @Shadow private float scrollPosition;
    @Shadow private boolean scrollbarClicked;
    @Shadow private boolean canApplyDyePattern;
    @Shadow private int visibleTopRow;
    @Shadow private int getRows() { return 0; }
    @Shadow private void drawBanner(RegistryEntry<BannerPattern> pattern, int x, int y) { throw new AssertionError(); }

    @Unique private GlassScrollbar s1mp1e$bar;
    @Unique private Fade s1mp1e$openFade;
    @Unique private boolean s1mp1e$opened;
    @Unique private boolean s1mp1e$sliding;
    @Unique private int s1mp1e$glideBase;
    @Unique private float s1mp1e$glideFracPx;

    @Unique private int s1mp1e$px() { return ((HandledScreenAccessor) (Object) this).s1mp1e$x(); }
    @Unique private int s1mp1e$py() { return ((HandledScreenAccessor) (Object) this).s1mp1e$y(); }
    @Unique private int s1mp1e$bgH() { return ((HandledScreenAccessor) (Object) this).s1mp1e$backgroundHeight(); }
    @Unique private LoomScreen s1mp1e$self() { return (LoomScreen) (Object) this; }
    @Unique private LoomScreenHandler s1mp1e$handler() {
        return (LoomScreenHandler) ((HandledScreen<?>) (Object) this).getScreenHandler();
    }
    @Unique private float s1mp1e$fade() { return s1mp1e$openFade == null ? 1f : s1mp1e$openFade.value(); }

    /** Body PNG → glass panel (over the vanilla dim); scroller sprite → glass scrollbar + glide state; 14x14 pattern
     *  frames suppressed during a glide; everything else vanilla. */
    @Redirect(method = "drawBackground",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screen/ingame/LoomScreen;"
                            + "drawTexture(Lnet/minecraft/client/util/math/MatrixStack;IIIIII)V"))
    private void s1mp1e$blit(LoomScreen self, MatrixStack matrices, int x, int y, int u, int v, int w, int h) {
        boolean glass = GlassProgram.ensureReady() && GlassProgram.usable();
        if (glass) {
            int px = s1mp1e$px(), py = s1mp1e$py();
            if (x == px && y == py && u == 0 && v == 0) {                 // body PNG -> glass panel
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
                s1mp1e$restoreVanillaBlitState();
                return;
            }
            if (w == 12 && h == 15) {                                     // the scroller sprite -> glass slider + glide
                s1mp1e$sliding = false;
                if (s1mp1e$bar == null) s1mp1e$bar = new GlassScrollbar();
                int offscreen = Math.max(0, getRows() - LG_VIS);
                int row = MathHelper.clamp(visibleTopRow, 0, offscreen);
                float ratio = offscreen <= 0 ? 0f : (float) row / offscreen;
                MinecraftClient mc = MinecraftClient.getInstance();
                double my = mc.mouse.getY() * (double) mc.getWindow().getScaledHeight() / (double) mc.getWindow().getHeight();
                // loom track: thumb 12x15, top at topPos+13, thumb-top travel 41 (vanilla y = j+13+(int)(41*scrollPosition))
                GlassScrollbar.run(s1mp1e$bar, matrices, x + w / 2f, py + 13f, 41f, 15f,
                        ratio, canApplyDyePattern, scrollbarClicked && canApplyDyePattern, my, s1mp1e$fade());
                if (canApplyDyePattern && offscreen > 0) {
                    float easedRows = s1mp1e$bar.pos() * offscreen;
                    if (Math.abs(easedRows - row) > 0.02f) {
                        s1mp1e$sliding = true;
                        s1mp1e$glideBase = MathHelper.clamp((int) Math.floor(easedRows), 0, offscreen);
                        s1mp1e$glideFracPx = (easedRows - s1mp1e$glideBase) * LG_CELL;
                    }
                }
                s1mp1e$restoreVanillaBlitState();
                return;
            }
            if (w == 14 && h == 14) {                                     // pattern cell frame — suppressed during glide
                if (!s1mp1e$sliding) self.drawTexture(matrices, x, y, u, v, w, h);
                return;
            }
        }
        self.drawTexture(matrices, x, y, u, v, w, h);
    }

    /** Suppress the vanilla banner preview on each pattern cell during a glide (redrawn by the overlay). */
    @Redirect(method = "drawBackground",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screen/ingame/LoomScreen;"
                            + "drawBanner(Lnet/minecraft/util/registry/RegistryEntry;II)V"))
    private void s1mp1e$loomBanner(LoomScreen self, RegistryEntry<BannerPattern> pattern, int x, int y) {
        if (!s1mp1e$sliding) this.drawBanner(pattern, x, y);   // self == this; call the shadow
    }

    /** Redraw the visible patterns at their absolute rows, offset sub-pixel by the eased value (via a model-view
     *  translate that moves BOTH the frame sprite and its banner model), clipped to the 4-row window. */
    @Inject(method = "drawBackground", at = @At("TAIL"))
    private void s1mp1e$loomOverlay(MatrixStack matrices, float delta, int mouseX, int mouseY, CallbackInfo ci) {
        if (!s1mp1e$sliding) return;
        LoomScreenHandler h = s1mp1e$handler();
        List<RegistryEntry<BannerPattern>> patterns = h.getBannerPatterns();
        int selected = h.getSelectedPattern();
        int px = s1mp1e$px(), py = s1mp1e$py(), bgH = s1mp1e$bgH();
        int x0 = px + LG_PX0, y0 = py + LG_PY0;

        DrawableHelper.enableScissor(x0, y0, x0 + LG_COLS * LG_CELL, y0 + LG_VIS * LG_CELL);
        DiffuseLighting.disableGuiDepthLighting();               // match the vanilla pattern-loop lighting state
        MatrixStack mv = RenderSystem.getModelViewStack();
        mv.push();
        mv.translate(0f, -s1mp1e$glideFracPx, 0f);              // fractional glide: frame sprite + banner share it
        RenderSystem.applyModelViewMatrix();
        try {
            for (int vr = 0; vr <= LG_VIS; vr++) {
                int row = s1mp1e$glideBase + vr;
                int cy = y0 + vr * LG_CELL;                     // absolute; the model-view supplies -fracPx
                for (int c = 0; c < LG_COLS; c++) {
                    int idx = row * LG_COLS + c;
                    if (idx < 0 || idx >= patterns.size()) continue;
                    int cx = x0 + c * LG_CELL;
                    s1mp1e$restoreVanillaBlitState();           // drawBanner unbinds the shader; rebind per cell
                    s1mp1e$self().drawTexture(matrices, cx, cy, 0, idx == selected ? bgH + 14 : bgH, LG_CELL, LG_CELL);
                    this.drawBanner(patterns.get(idx), cx, cy);
                }
            }
        } finally {
            mv.pop();
            RenderSystem.applyModelViewMatrix();
            DiffuseLighting.enableGuiDepthLighting();
            DrawableHelper.disableScissor();
        }

        float topK = s1mp1e$glideBase > 0 || s1mp1e$glideFracPx > 0.5f ? 1f : 0f;
        float botK = (s1mp1e$glideBase + LG_VIS) < getRows() ? 1f : 0f;
        GlassWidgets.scrollEdges(x0, y0, x0 + LG_COLS * LG_CELL, y0 + LG_VIS * LG_CELL, 5f, topK, botK, s1mp1e$fade());
    }

    /** A click mid-glide snaps to the target row first, so it always selects the pattern drawn under the cursor. */
    @Inject(method = "mouseClicked", at = @At("HEAD"))
    private void s1mp1e$snapOnClick(double mx, double my, int button, CallbackInfoReturnable<Boolean> cir) {
        if (s1mp1e$sliding && s1mp1e$bar != null) {
            s1mp1e$bar.snapToTarget();
            s1mp1e$sliding = false;
        }
    }

    @Unique private static void s1mp1e$restoreVanillaBlitState() {
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.setShaderTexture(0, S1MP1E_TEX);
    }
}
