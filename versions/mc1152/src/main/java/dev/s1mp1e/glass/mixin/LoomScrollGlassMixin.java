package dev.s1mp1e.glass.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.s1mp1e.client.gui.GlassScrollbar;
import dev.s1mp1e.client.gui.GlassWidgets;
import dev.s1mp1e.glass.anim.Fade;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.PanelGhost;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.LoomScreen;
import net.minecraft.block.entity.BannerPattern;
import net.minecraft.client.render.DiffuseLighting;
import net.minecraft.container.LoomContainer;
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
 * Loom pattern grid -> the shared vertical glass slider (C) + silky sub-pixel pattern-row glide (D). The 1.15.2 FF-Fabric
 * port of 1.20.1's {@code LoomScrollGlassMixin}. The pattern list stays drawn: the shared {@code HandledScreenGlassMixin}
 * delegates to this screen's own {@code drawBackground} (rather than swallowing it), this mixin replaces only the body
 * PNG with the glass panel and the scroller sprite with the glass scrollbar, and — while the eased offset differs from
 * the logical row — suppresses the vanilla pattern buttons (14x14 background sprites + their 3D banner previews) and
 * redraws the eased 5-row window itself, scissored to the 4-row viewport.
 *
 * <h3>Why "redirect every blit + dispatch by args" instead of ordinals</h3>
 * {@code LoomScreen.drawBackground} issues several {@code blit(IIIIII)} calls whose ORDER is data-dependent: the four
 * empty-slot placeholder icons (banner / dye / pattern / output) are drawn only when that slot is empty, so a fixed
 * {@code @At ordinal} cannot pin the scroller or body blit. This mixin redirects every {@code blit(IIIIII)} in
 * {@code drawBackground} and dispatches on the sprite's UVs: {@code u==0,v==0} is the body PNG (the only one at the
 * texture origin); {@code u==232,w==12,h==15} is the scroller thumb; a {@code u==0,w==14,h==14} sprite is a
 * pattern-button background (its {@code v} carries the base/selected/hover row); everything else (placeholders, the "too
 * many patterns" warning) passes through untouched, so the pattern list is preserved.
 *
 * <h3>Glide alignment (D) — no GL translate for the banners</h3>
 * {@code method_22692} renders a button's banner with its OWN fresh {@code MatrixStack} positioned by INTEGER (x,y)
 * params, so it ignores the GL modelview — a {@code RenderSystem.translatef} would move the button backgrounds but not
 * the banners. To keep them aligned the eased offset is baked into the INTEGER draw Y of BOTH the background and the
 * banner ({@code round(baseY - fracPx)}); at 60 fps the whole grid steps in 1 px increments over the ~150 ms glide,
 * which reads as smooth while never letting a banner drift from its frame. The rows are clamped to the real pattern
 * count so no banner index goes out of range.
 */
@Mixin(LoomScreen.class)
public abstract class LoomScrollGlassMixin {

    @Shadow private float scrollPosition;
    @Shadow private boolean scrollbarClicked;
    @Shadow private boolean canApplyDyePattern;
    @Shadow private int firstPatternButtonId;
    @Shadow @org.spongepowered.asm.mixin.Final private static int PATTERN_BUTTON_ROW_COUNT;
    // Private target method -> @Shadow with a stub body (not abstract), the verified pattern for private-method shadows.
    @Shadow private void method_22692(int patternId, int x, int y) { throw new AssertionError(); }

    @Unique private static final Identifier S1MP1E_TEX = new Identifier("textures/gui/container/loom.png");

    @Unique private GlassScrollbar s1mp1e$scrollbar;
    @Unique private boolean s1mp1e$sliding;
    @Unique private int s1mp1e$glideBase;
    @Unique private float s1mp1e$glideFracPx;
    @Unique private Fade s1mp1e$openFade;
    @Unique private boolean s1mp1e$opened;

    @Unique private int s1mp1e$px() { return ((ContainerScreenTopAccessor) (Object) this).s1mp1e$left(); }
    @Unique private int s1mp1e$py() { return ((ContainerScreenTopAccessor) (Object) this).s1mp1e$top(); }
    @Unique private int s1mp1e$bgV() { return ((ContainerScreenTopAccessor) (Object) this).s1mp1e$ySize(); }

    @Unique private LoomContainer s1mp1e$container() {
        Object c = ((LoomScreen) (Object) this).getContainer();
        return c instanceof LoomContainer ? (LoomContainer) c : null;
    }

    /** Every {@code drawBackground} blit -> glass panel / glass scrollbar / suppressed pattern-button bg / passthrough. */
    @Redirect(method = "drawBackground",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screen/ingame/LoomScreen;blit(IIIIII)V"))
    private void s1mp1e$blit(LoomScreen self, int x, int y, int u, int v, int w, int h,
                             float delta, int mouseX, int mouseY) {
        if (u == 0 && v == 0) {                        // body PNG -> glass panel
            s1mp1e$sliding = false;                     // reset once per frame (the body blit always runs first)
            s1mp1e$body(self, x, y, w, h);
            return;
        }
        if (u == 232 && w == 12 && h == 15) {           // scroller thumb -> glass scrollbar (+ glide state)
            s1mp1e$scroller(self, x, y, mouseY);
            return;
        }
        if (s1mp1e$sliding && u == 0 && w == 14 && h == 14) return;   // suppress vanilla pattern-bg during a glide
        self.blit(x, y, u, v, w, h);                    // placeholders / pattern-button backgrounds / warning
    }

    @Unique
    private void s1mp1e$body(LoomScreen self, int x, int y, int w, int h) {
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) {
            self.blit(x, y, 0, 0, w, h);
            return;
        }
        if (s1mp1e$openFade == null) s1mp1e$openFade = new Fade(0f, PanelGhost.FADE_MS);
        // Frame-primary panel with no pre-dim grabNow to fold onto -> FORCE a fresh copy every frame (R4): a deduped
        // grab() could fold onto the previous frame's tooltip forceGrab at high fps and self-ghost. (mc1165 parity.)
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
    }

    @Unique
    private void s1mp1e$scroller(LoomScreen self, int sx, int sy, int mouseY) {
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) {
            self.blit(sx, sy, 232, canApplyDyePattern ? 0 : 12, 12, 15);
            return;
        }
        if (s1mp1e$scrollbar == null) s1mp1e$scrollbar = new GlassScrollbar();
        int rc = PATTERN_BUTTON_ROW_COUNT - 4;
        boolean active = canApplyDyePattern && rc > 0;
        int row = rc <= 0 ? 0 : s1mp1e$clamp(Math.round(scrollPosition * rc), 0, rc);
        float targetRatio = rc <= 0 ? 0f : (float) row / rc;
        float fade = s1mp1e$openFade == null ? 1f : s1mp1e$openFade.value();
        // loom track: thumb 12x15, top at py+13, thumb-top travel 41 (vanilla sprite y = py+13+(int)(41*scrollPosition)).
        GlassScrollbar.run(s1mp1e$scrollbar, sx + 6f, s1mp1e$py() + 13f, 41f, 15f,
                targetRatio, active, scrollbarClicked && active, mouseY, fade);
        if (active && rc > 0) {
            float easedRows = s1mp1e$scrollbar.pos() * rc;
            if (Math.abs(easedRows - row) > 0.02f) {
                s1mp1e$sliding = true;
                s1mp1e$glideBase = s1mp1e$clamp((int) Math.floor(easedRows), 0, rc);
                s1mp1e$glideFracPx = (easedRows - s1mp1e$glideBase) * 14f;
            }
        }
    }

    /** During a glide, suppress the vanilla banner previews; the eased overlay ({@link #s1mp1e$glideOverlay}) redraws them. */
    @Redirect(method = "drawBackground",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screen/ingame/LoomScreen;method_22692(III)V"))
    private void s1mp1e$suppressBanner(LoomScreen self, int patternId, int bx, int by) {
        if (s1mp1e$sliding) return;   // suppressed during a glide; the eased overlay redraws all banners
        this.method_22692(patternId, bx, by);
    }

    /** Draw the eased 5-row pattern window (backgrounds + banners at matching INTEGER y) before the button block closes. */
    @Inject(method = "drawBackground",
            at = @At(value = "INVOKE", shift = At.Shift.BEFORE,
                     target = "Lnet/minecraft/client/render/DiffuseLighting;enableGuiDepthLighting()V"))
    private void s1mp1e$glideOverlay(float delta, int mouseX, int mouseY, CallbackInfo ci) {
        if (!s1mp1e$sliding) return;
        LoomContainer h = s1mp1e$container();
        if (h == null) return;
        int selected = h.getSelectedPattern();
        // The loom shows pattern indices [0, COUNT-5) (the 5 special patterns are excluded), so this is the exact count
        // vanilla's loop bounds itself with (index < BannerPattern.COUNT - 5) — no banner index can go out of range.
        int total = BannerPattern.COUNT - 5;
        int px = s1mp1e$px(), py = s1mp1e$py(), bgV = s1mp1e$bgV();
        LoomScreen scr = (LoomScreen) (Object) this;
        int fracPx = Math.round(s1mp1e$glideFracPx);
        GlassWidgets.beginScissor(px + 60, py + 13, px + 60 + 4 * 14, py + 13 + 4 * 14);
        // backgrounds (loom.png, u=0, v = containerHeight [+14 selected]) at the eased integer y
        MinecraftClient.getInstance().getTextureManager().bindTexture(S1MP1E_TEX);
        RenderSystem.color4f(1f, 1f, 1f, 1f);
        for (int lr = 0; lr <= 4; lr++) {
            for (int col = 0; col < 4; col++) {
                int idx = (s1mp1e$glideBase + lr) * 4 + col;
                if (idx < 0 || idx >= total) continue;
                int bx = px + 60 + col * 14;
                int by = py + 13 + lr * 14 - fracPx;
                scr.blit(bx, by, 0, bgV + (idx == selected ? 14 : 0), 14, 14);
            }
        }
        GlassWidgets.resetColorCache();
        // banners at the SAME eased integer y (method_22692 uses its own MatrixStack -> position must be baked into y)
        DiffuseLighting.enableGuiDepthLighting();
        for (int lr = 0; lr <= 4; lr++) {
            for (int col = 0; col < 4; col++) {
                int idx = (s1mp1e$glideBase + lr) * 4 + col;
                if (idx < 0 || idx >= total) continue;
                int bx = px + 60 + col * 14;
                int by = py + 13 + lr * 14 - fracPx;
                this.method_22692(idx, bx, by);
            }
        }
        GlassWidgets.endScissor();
        GlassWidgets.resetColorCache();
    }

    /** A click while the grid is mid-glide snaps to the target row first (acts on the pattern drawn under the cursor). */
    @Inject(method = "mouseClicked", at = @At("HEAD"))
    private void s1mp1e$snapOnClick(double mx, double my, int button, CallbackInfoReturnable<Boolean> cir) {
        if (s1mp1e$sliding && s1mp1e$scrollbar != null) {
            s1mp1e$scrollbar.snapToTarget();
            s1mp1e$sliding = false;
        }
    }

    @Unique
    private static int s1mp1e$clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
