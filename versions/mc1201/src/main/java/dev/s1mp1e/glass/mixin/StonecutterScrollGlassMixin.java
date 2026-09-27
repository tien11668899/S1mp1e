package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.GlassScrollbar;
import dev.s1mp1e.glass.anim.Fade;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.PanelGhost;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.StonecutterScreen;
import net.minecraft.recipe.StonecuttingRecipe;
import net.minecraft.screen.StonecutterScreenHandler;
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
 * Stonecutter recipe list → the shared vertical glass slider (C) + silky sub-pixel row glide (D). The recipe list itself
 * is re-enabled here: the shared {@code HandledScreenGlassMixin} draws the glass panel then delegates to this screen's
 * {@code drawBackground} for the list, and this mixin suppresses the vanilla dim + body PNG (the glass panel already
 * replaced them), turns the scroller sprite into the glass scrollbar, and — while the eased scroll offset differs from the
 * logical row — redraws the recipe grid translated by the fractional offset, scissored to the 3-row window. A mid-glide
 * click snaps to the target row first (click correctness).
 */
@Mixin(StonecutterScreen.class)
public abstract class StonecutterScrollGlassMixin {

    @Unique private static final Identifier S1MP1E_TEX = new Identifier("textures/gui/container/stonecutter.png");

    @Shadow private float scrollAmount;
    @Shadow private int scrollOffset;
    @Shadow private boolean canCraft;
    @Shadow private boolean mouseClicked;
    @Shadow protected abstract int getMaxScroll();

    @Unique private GlassScrollbar s1mp1e$scrollbar;
    @Unique private boolean s1mp1e$sliding;
    @Unique private int s1mp1e$glideBase;
    @Unique private float s1mp1e$glideFracPx;
    @Unique private Fade s1mp1e$openFade;
    @Unique private boolean s1mp1e$opened;

    @Unique private int s1mp1e$px() { return ((HandledScreenAccessor) (Object) this).s1mp1e$x(); }
    @Unique private int s1mp1e$py() { return ((HandledScreenAccessor) (Object) this).s1mp1e$y(); }
    @Unique private int s1mp1e$bgH() { return ((HandledScreenAccessor) (Object) this).s1mp1e$backgroundHeight(); }
    @Unique private StonecutterScreenHandler s1mp1e$handler() {
        return (StonecutterScreenHandler) ((net.minecraft.client.gui.screen.ingame.HandledScreen<?>) (Object) this).getScreenHandler();
    }

    /** Body PNG → the glass container panel (drawn over the vanilla dim, which renderBackground already drew). */
    @Redirect(method = "drawBackground",
            at = @At(value = "INVOKE", ordinal = 0,
                     target = "Lnet/minecraft/client/gui/DrawContext;drawTexture(Lnet/minecraft/util/Identifier;IIIIII)V"))
    private void s1mp1e$body(DrawContext self, Identifier tex, int x, int y, int u, int v, int w, int h) {
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) {
            self.drawTexture(tex, x, y, u, v, w, h);
            return;
        }
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
    }

    /** Scroller sprite → the shared vertical glass slider, plus the per-frame glide-state computation. */
    @Redirect(method = "drawBackground",
            at = @At(value = "INVOKE", ordinal = 1,
                     target = "Lnet/minecraft/client/gui/DrawContext;drawTexture(Lnet/minecraft/util/Identifier;IIIIII)V"))
    private void s1mp1e$scroller(DrawContext self, Identifier tex, int sx, int sy, int u, int v, int w, int h,
                                 DrawContext ctxEnc, float delta, int mouseX, int mouseY) {
        s1mp1e$sliding = false;
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) {
            self.drawTexture(tex, sx, sy, u, v, w, h);
            return;
        }
        if (s1mp1e$scrollbar == null) s1mp1e$scrollbar = new GlassScrollbar();
        int rc = getMaxScroll();
        boolean active = canCraft && s1mp1e$handler().getAvailableRecipeCount() > 12;
        int row = rc <= 0 ? 0 : MathHelper.clamp(Math.round(scrollAmount * rc), 0, rc);
        float targetRatio = rc <= 0 ? 0f : (float) row / rc;
        // stonecutter track: thumb 12x15, top at topPos+15, thumb-top travel 41 (vanilla sprite: y = j+15+(int)(41*amount))
        GlassScrollbar.run(s1mp1e$scrollbar, ctxEnc, sx + w / 2f, s1mp1e$py() + 15f, 41f, 15f,
                targetRatio, active, mouseClicked && active, mouseY, 1.0f);
        if (active && rc > 0) {
            float easedRows = s1mp1e$scrollbar.pos() * rc;
            if (Math.abs(easedRows - row) > 0.02f) {
                s1mp1e$sliding = true;
                s1mp1e$glideBase = MathHelper.clamp((int) Math.floor(easedRows), 0, rc);
                s1mp1e$glideFracPx = (easedRows - s1mp1e$glideBase) * 18f;
            }
        }
    }

    @Inject(method = "renderRecipeBackground", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$glideBackgrounds(DrawContext ctx, int mouseX, int mouseY, int x, int y, int scrollEnd, CallbackInfo ci) {
        if (!s1mp1e$sliding) return;
        ci.cancel();
        StonecutterScreenHandler h = s1mp1e$handler();
        int count = h.getAvailableRecipeCount();
        int selected = h.getSelectedRecipe();
        int px = s1mp1e$px(), py = s1mp1e$py(), bgH = s1mp1e$bgH();
        ctx.enableScissor(px + 52, py + 14, px + 52 + 64, py + 14 + 54);
        ctx.getMatrices().push();
        ctx.getMatrices().translate(0f, -s1mp1e$glideFracPx, 0f);
        for (int lr = 0; lr <= 3; lr++) {
            for (int col = 0; col < 4; col++) {
                int idx = (s1mp1e$glideBase + lr) * 4 + col;
                if (idx < 0 || idx >= count) continue;
                int k = px + 52 + col * 16;
                int m = py + 14 + lr * 18 + 2;
                int n = bgH + (idx == selected ? 18 : 0);
                ctx.drawTexture(S1MP1E_TEX, k, m - 1, 0, n, 16, 18);
            }
        }
        ctx.getMatrices().pop();
        ctx.disableScissor();
    }

    @Inject(method = "renderRecipeIcons", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$glideIcons(DrawContext ctx, int x, int y, int scrollEnd, CallbackInfo ci) {
        if (!s1mp1e$sliding) return;
        ci.cancel();
        StonecutterScreenHandler h = s1mp1e$handler();
        List<StonecuttingRecipe> list = h.getAvailableRecipes();
        int count = h.getAvailableRecipeCount();
        int px = s1mp1e$px(), py = s1mp1e$py();
        var rm = MinecraftClient.getInstance().world.getRegistryManager();
        ctx.enableScissor(px + 52, py + 14, px + 52 + 64, py + 14 + 54);
        ctx.getMatrices().push();
        ctx.getMatrices().translate(0f, -s1mp1e$glideFracPx, 0f);
        for (int lr = 0; lr <= 3; lr++) {
            for (int col = 0; col < 4; col++) {
                int idx = (s1mp1e$glideBase + lr) * 4 + col;
                if (idx < 0 || idx >= count) continue;
                int k = px + 52 + col * 16;
                int m = py + 14 + lr * 18 + 2;
                ctx.drawItem(list.get(idx).getOutput(rm), k, m);
            }
        }
        ctx.getMatrices().pop();
        ctx.disableScissor();
    }

    /** A click while the list is mid-glide snaps to the target row first (acts on the recipe drawn under the cursor). */
    @Inject(method = "mouseClicked", at = @At("HEAD"))
    private void s1mp1e$snapOnClick(double mx, double my, int button, CallbackInfoReturnable<Boolean> cir) {
        if (s1mp1e$sliding && s1mp1e$scrollbar != null) {
            s1mp1e$scrollbar.snapToTarget();
            s1mp1e$sliding = false;
        }
    }
}
