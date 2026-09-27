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
import net.minecraft.client.gui.screen.ingame.StonecutterScreen;
import net.minecraft.client.render.DiffuseLighting;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.container.StonecutterContainer;
import net.minecraft.recipe.StonecuttingRecipe;
import net.minecraft.util.Identifier;
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
 * Stonecutter recipe list -> the shared vertical glass slider (C) + silky sub-pixel row glide (D). The 1.15.2 FF-Fabric
 * port of 1.20.1's {@code StonecutterScrollGlassMixin}. The recipe list stays drawn: the shared
 * {@code HandledScreenGlassMixin} delegates to this screen's own {@code drawBackground} (rather than swallowing it), this
 * mixin redirects only the body-PNG blit to the glass panel, turns the scroller sprite into the glass scrollbar, and —
 * while the eased scroll offset differs from the logical row — redraws the recipe grid translated by the fractional
 * offset, scissored to the 3-row window. A mid-glide click snaps to the target row first (click correctness).
 *
 * <p>Everything runs at pose-identity ({@code drawBackground} is before {@code render}'s {@code translate(x,y)}), so the
 * eased redraw applies its own {@code RenderSystem} translate and the raw {@code glScissor} uses absolute window coords.
 */
@Mixin(StonecutterScreen.class)
public abstract class StonecutterScrollGlassMixin {

    @Unique private static final Identifier S1MP1E_TEX = new Identifier("textures/gui/container/stonecutter.png");

    @Shadow private float scrollAmount;
    @Shadow private boolean canCraft;
    @Shadow private boolean mouseClicked;
    @Shadow protected abstract int getMaxScroll();

    @Unique private GlassScrollbar s1mp1e$scrollbar;
    @Unique private boolean s1mp1e$sliding;
    @Unique private int s1mp1e$glideBase;
    @Unique private float s1mp1e$glideFracPx;
    @Unique private Fade s1mp1e$openFade;
    @Unique private boolean s1mp1e$opened;

    @Unique private int s1mp1e$px() { return ((ContainerScreenTopAccessor) (Object) this).s1mp1e$left(); }
    @Unique private int s1mp1e$py() { return ((ContainerScreenTopAccessor) (Object) this).s1mp1e$top(); }
    @Unique private int s1mp1e$bgH() { return ((ContainerScreenTopAccessor) (Object) this).s1mp1e$ySize(); }

    @Unique private StonecutterContainer s1mp1e$container() {
        Object c = ((StonecutterScreen) (Object) this).getContainer();
        return c instanceof StonecutterContainer ? (StonecutterContainer) c : null;
    }

    /** Body PNG -> the glass container panel (drawn over the vanilla dim, which renderBackground already drew). */
    @Redirect(method = "drawBackground",
            at = @At(value = "INVOKE", ordinal = 0,
                     target = "Lnet/minecraft/client/gui/screen/ingame/StonecutterScreen;blit(IIIIII)V"))
    private void s1mp1e$body(StonecutterScreen self, int x, int y, int u, int v, int w, int h) {
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) {
            self.blit(x, y, u, v, w, h);
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

    /** Scroller sprite -> the shared vertical glass slider, plus the per-frame glide-state computation. */
    @Redirect(method = "drawBackground",
            at = @At(value = "INVOKE", ordinal = 1,
                     target = "Lnet/minecraft/client/gui/screen/ingame/StonecutterScreen;blit(IIIIII)V"))
    private void s1mp1e$scroller(StonecutterScreen self, int sx, int sy, int u, int v, int w, int h,
                                 float delta, int mouseX, int mouseY) {
        s1mp1e$sliding = false;
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) {
            self.blit(sx, sy, u, v, w, h);
            return;
        }
        if (s1mp1e$scrollbar == null) s1mp1e$scrollbar = new GlassScrollbar();
        StonecutterContainer h2 = s1mp1e$container();
        int rc = getMaxScroll();
        boolean active = canCraft && h2 != null && h2.getAvailableRecipeCount() > 12;
        int row = rc <= 0 ? 0 : s1mp1e$clamp(Math.round(scrollAmount * rc), 0, rc);
        float targetRatio = rc <= 0 ? 0f : (float) row / rc;
        float fade = s1mp1e$openFade == null ? 1f : s1mp1e$openFade.value();
        // stonecutter track: thumb 12x15, top at py+15, thumb-top travel 41 (vanilla sprite y = py+15+(int)(41*amount)).
        GlassScrollbar.run(s1mp1e$scrollbar, sx + w / 2f, s1mp1e$py() + 15f, 41f, 15f,
                targetRatio, active, mouseClicked && active, mouseY, fade);
        if (active && rc > 0) {
            float easedRows = s1mp1e$scrollbar.pos() * rc;
            if (Math.abs(easedRows - row) > 0.02f) {
                s1mp1e$sliding = true;
                s1mp1e$glideBase = s1mp1e$clamp((int) Math.floor(easedRows), 0, rc);
                s1mp1e$glideFracPx = (easedRows - s1mp1e$glideBase) * 18f;
            }
        }
    }

    @Inject(method = "renderRecipeBackground", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$glideBackgrounds(int mouseX, int mouseY, int x, int y, int scrollEnd, CallbackInfo ci) {
        if (!s1mp1e$sliding) return;
        ci.cancel();
        StonecutterContainer h = s1mp1e$container();
        if (h == null) return;
        int count = h.getAvailableRecipeCount();
        int selected = h.getSelectedRecipe();
        int px = s1mp1e$px(), py = s1mp1e$py(), bgH = s1mp1e$bgH();
        StonecutterScreen scr = (StonecutterScreen) (Object) this;
        MinecraftClient.getInstance().getTextureManager().bindTexture(S1MP1E_TEX);
        RenderSystem.color4f(1f, 1f, 1f, 1f);
        GlassWidgets.beginScissor(px + 52, py + 14, px + 52 + 64, py + 14 + 54);
        RenderSystem.pushMatrix();
        RenderSystem.translatef(0f, -s1mp1e$glideFracPx, 0f);
        for (int lr = 0; lr <= 3; lr++) {
            for (int col = 0; col < 4; col++) {
                int idx = (s1mp1e$glideBase + lr) * 4 + col;
                if (idx < 0 || idx >= count) continue;
                int k = px + 52 + col * 16;
                int m = py + 14 + lr * 18 + 2;
                int n = bgH + (idx == selected ? 18 : 0);
                scr.blit(k, m - 1, 0, n, 16, 18);
            }
        }
        RenderSystem.popMatrix();
        GlassWidgets.endScissor();
        GlassWidgets.resetColorCache();
    }

    @Inject(method = "renderRecipeIcons", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$glideIcons(int x, int y, int scrollEnd, CallbackInfo ci) {
        if (!s1mp1e$sliding) return;
        ci.cancel();
        StonecutterContainer h = s1mp1e$container();
        if (h == null) return;
        List<StonecuttingRecipe> list = h.getAvailableRecipes();
        int count = h.getAvailableRecipeCount();
        int px = s1mp1e$px(), py = s1mp1e$py();
        GlassWidgets.beginScissor(px + 52, py + 14, px + 52 + 64, py + 14 + 54);
        RenderSystem.pushMatrix();
        RenderSystem.translatef(0f, -s1mp1e$glideFracPx, 0f);
        GlassWidgets.resetColorCache();
        DiffuseLighting.enableGuiDepthLighting();
        RenderSystem.enableRescaleNormal();
        ItemRenderer ir = MinecraftClient.getInstance().getItemRenderer();
        try {
            for (int lr = 0; lr <= 3; lr++) {
                for (int col = 0; col < 4; col++) {
                    int idx = (s1mp1e$glideBase + lr) * 4 + col;
                    if (idx < 0 || idx >= count) continue;
                    int k = px + 52 + col * 16;
                    int m = py + 14 + lr * 18 + 2;
                    ir.renderGuiItem(list.get(idx).getOutput(), k, m);
                }
            }
        } finally {
            RenderSystem.disableRescaleNormal();
            GlassWidgets.resetColorCache();
            RenderSystem.enableAlphaTest();
            RenderSystem.enableBlend();
            RenderSystem.popMatrix();
            GlassWidgets.endScissor();
        }
    }

    /** A click while the list is mid-glide snaps to the target row first (acts on the recipe drawn under the cursor). */
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
