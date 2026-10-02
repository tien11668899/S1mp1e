package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.GlassScrollbar;
import dev.s1mp1e.client.gui.Scissor;
import dev.s1mp1e.glass.anim.Fade;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.PanelGhost;
import dev.s1mp1e.glass.render.SceneCapture;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.StonecutterScreen;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.client.util.math.MatrixStack;
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
 * Stonecutter recipe list → the shared vertical glass slider (C) + silky sub-pixel row glide (D). 1.18.2
 * ({@link MatrixStack} core-profile) port; byte-for-byte the 1.19.2 sibling except the scissor helper (1.18.2's
 * {@code DrawableHelper} lacks {@code enableScissor}, so {@link Scissor} maps scaled-GUI coords into the framebuffer).
 *
 * <p>The recipe list is re-enabled by the shared delegation (see {@code HandledScreenGlassMixin}: for a
 * {@link StonecutterScreen} it runs the concrete {@code drawBackground} instead of swallowing it, so the recipe grid
 * inside it draws). Here:
 * <ul>
 *   <li>the body PNG (ordinal-0 {@code drawTexture}) becomes the glass container panel (fresh {@code grabNow}, R4);</li>
 *   <li>the scroller sprite (ordinal-1 {@code drawTexture}) becomes the glass slider, and — because the glass draws
 *       unbind MC's shader — the vanilla {@code position_tex} shader + white colour + stonecutter texture are restored
 *       here so the following {@code renderRecipeBackground} button blits render (they rely on the bound shader);</li>
 *   <li>while the eased scroll offset differs from the logical row, {@code renderRecipeBackground}/{@code
 *       renderRecipeIcons} are cancelled and redrawn at their absolute rows translated by the fractional offset,
 *       scissored to the 3-row (54 px) window — the config-menu silky glide;</li>
 *   <li>a mid-glide click snaps to the target row first, so it always selects the recipe drawn under the cursor.</li>
 * </ul>
 * The vanilla logical scroll ({@code scrollOffset}/{@code scrollAmount}) is never changed, so clicks / hit-testing stay
 * correct and the eased draw settles exactly onto the vanilla row at rest.
 */
@Mixin(StonecutterScreen.class)
public abstract class StonecutterScrollGlassMixin
        implements dev.s1mp1e.client.gui.GlideProbe, dev.s1mp1e.client.gui.ScrollDragOwner {

    @Unique private static final Identifier S1MP1E_TEX =
            new Identifier("textures/gui/container/stonecutter.png");

    @Shadow private float scrollAmount;
    @Shadow private int scrollOffset;
    @Shadow private boolean canCraft;
    @Shadow private boolean mouseClicked;
    @Shadow protected abstract int getMaxScroll();

    @Unique private GlassScrollbar s1mp1e$bar;
    @Unique private boolean s1mp1e$sliding;
    @Unique private int s1mp1e$glideBase;
    @Unique private float s1mp1e$glideFracPx;
    @Unique private final dev.s1mp1e.glass.render.ContainerGlass.State s1mp1e$glass = new dev.s1mp1e.glass.render.ContainerGlass.State();

    @Unique private int s1mp1e$px() { return ((HandledScreenAccessor) (Object) this).s1mp1e$x(); }
    @Unique private int s1mp1e$py() { return ((HandledScreenAccessor) (Object) this).s1mp1e$y(); }
    @Unique private int s1mp1e$bgH() { return ((HandledScreenAccessor) (Object) this).s1mp1e$backgroundHeight(); }
    @Unique private StonecutterScreenHandler s1mp1e$handler() {
        return (StonecutterScreenHandler) ((HandledScreen<?>) (Object) this).getScreenHandler();
    }

    /** Body PNG → the glass container panel (drawn over the vanilla dim that {@code renderBackground} drew first). */
    @Redirect(method = "drawBackground",
            at = @At(value = "INVOKE", ordinal = 0,
                     target = "Lnet/minecraft/client/gui/screen/ingame/StonecutterScreen;"
                            + "drawTexture(Lnet/minecraft/client/util/math/MatrixStack;IIIIII)V"))
    private void s1mp1e$body(StonecutterScreen self, MatrixStack matrices, int x, int y, int u, int v, int w, int h,
                             MatrixStack matricesEnc, float delta, int mouseX, int mouseY) {
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) {
            self.drawTexture(matrices, x, y, u, v, w, h);
            return;
        }
        // panel + slot lattice + quick-craft highlight + hover pill (the newer lines' shared container glass; this
        // screen used to get the bare panel only, refracting the already-dimmed world)
        HandledScreenAccessor a = (HandledScreenAccessor) (Object) this;
        dev.s1mp1e.glass.render.ContainerGlass.draw(s1mp1e$glass, matrices, x, y, w, h,
                a.s1mp1e$handler().slots, a.s1mp1e$cursorDragSlots(), a.s1mp1e$cursorDragging(), mouseX, mouseY);
    }

    /** Scroller sprite → the glass slider + the per-frame glide-state computation; then restore MC's shader state. */
    @Redirect(method = "drawBackground",
            at = @At(value = "INVOKE", ordinal = 1,
                     target = "Lnet/minecraft/client/gui/screen/ingame/StonecutterScreen;"
                            + "drawTexture(Lnet/minecraft/client/util/math/MatrixStack;IIIIII)V"))
    private void s1mp1e$scroller(StonecutterScreen self, MatrixStack matrices, int sx, int sy, int u, int v, int w, int h) {
        s1mp1e$sliding = false;
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) {
            self.drawTexture(matrices, sx, sy, u, v, w, h);
            return;
        }
        if (s1mp1e$bar == null) s1mp1e$bar = new GlassScrollbar();
        int rc = getMaxScroll();
        boolean active = canCraft && s1mp1e$handler().getAvailableRecipeCount() > 12;
        int row = rc <= 0 ? 0 : MathHelper.clamp(Math.round(scrollAmount * rc), 0, rc);
        float targetRatio = rc <= 0 ? 0f : (float) row / rc;
        MinecraftClient mc = MinecraftClient.getInstance();
        double my = mc.mouse.getY() * (double) mc.getWindow().getScaledHeight() / (double) mc.getWindow().getHeight();
        // stonecutter track: thumb 12x15, top at topPos+15, thumb-top travel 41 (vanilla y = j+15+(int)(41*amount))
        GlassScrollbar.run(s1mp1e$bar, matrices, sx + w / 2f, s1mp1e$py() + 15f, 41f, 15f,
                targetRatio, active, mouseClicked && active, my, s1mp1e$glass.fade());
        if (active && rc > 0) {
            float easedRows = s1mp1e$bar.pos() * rc;
            if (Math.abs(easedRows - row) > 0.02f) {
                s1mp1e$sliding = true;
                s1mp1e$glideBase = MathHelper.clamp((int) Math.floor(easedRows), 0, rc);
                s1mp1e$glideFracPx = (easedRows - s1mp1e$glideBase) * 18f;
            }
        }
        // The glass draws unbind MC's shader; the following renderRecipeBackground button blits rely on the
        // position_tex shader + stonecutter texture being bound (vanilla set them once at drawBackground HEAD).
        s1mp1e$restoreVanillaBlitState();
    }

    @Unique private static void s1mp1e$restoreVanillaBlitState() {
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.setShaderTexture(0, S1MP1E_TEX);
    }

    @Inject(method = "renderRecipeBackground", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$glideBackgrounds(MatrixStack matrices, int mouseX, int mouseY, int x, int y, int scrollEnd, CallbackInfo ci) {
        if (!s1mp1e$sliding) return;
        ci.cancel();
        StonecutterScreenHandler hh = s1mp1e$handler();
        int count = hh.getAvailableRecipeCount();
        int selected = hh.getSelectedRecipe();
        int px = s1mp1e$px(), py = s1mp1e$py(), bgH = s1mp1e$bgH();
        s1mp1e$restoreVanillaBlitState();
        Scissor.enable(px + 52, py + 14, px + 52 + 64, py + 14 + 54);
        matrices.push();
        matrices.translate(0f, -s1mp1e$glideFracPx, 0f);
        for (int lr = 0; lr <= 3; lr++) {
            for (int col = 0; col < 4; col++) {
                int idx = (s1mp1e$glideBase + lr) * 4 + col;
                if (idx < 0 || idx >= count) continue;
                int k = px + 52 + col * 16;
                int m = py + 14 + lr * 18 + 2;
                int n = bgH + (idx == selected ? 18 : 0);
                self().drawTexture(matrices, k, m - 1, 0, n, 16, 18);
            }
        }
        matrices.pop();
        Scissor.disable();
    }

    @Inject(method = "renderRecipeIcons", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$glideIcons(int x, int y, int scrollEnd, CallbackInfo ci) {
        if (!s1mp1e$sliding) return;
        ci.cancel();
        StonecutterScreenHandler hh = s1mp1e$handler();
        List<StonecuttingRecipe> list = hh.getAvailableRecipes();
        int count = hh.getAvailableRecipeCount();
        int px = s1mp1e$px(), py = s1mp1e$py();
        ItemRenderer ir = MinecraftClient.getInstance().getItemRenderer();
        // renderRecipeIcons has no MatrixStack — GUI items compose the RenderSystem model-view stack, so translate it.
        Scissor.enable(px + 52, py + 14, px + 52 + 64, py + 14 + 54);
        MatrixStack mv = RenderSystem.getModelViewStack();
        mv.push();
        mv.translate(0f, -s1mp1e$glideFracPx, 0f);
        RenderSystem.applyModelViewMatrix();
        for (int lr = 0; lr <= 3; lr++) {
            for (int col = 0; col < 4; col++) {
                int idx = (s1mp1e$glideBase + lr) * 4 + col;
                if (idx < 0 || idx >= count) continue;
                int k = px + 52 + col * 16;
                int m = py + 14 + lr * 18 + 2;
                ir.renderInGuiWithOverrides(list.get(idx).getOutput(), k, m);
            }
        }
        mv.pop();
        RenderSystem.applyModelViewMatrix();
        Scissor.disable();
    }

    @Override
    public boolean s1mp1e$probeGliding() { return s1mp1e$sliding; }

    @Override
    public float s1mp1e$probeOffsetPx() {
        return s1mp1e$bar == null ? 0f : s1mp1e$bar.pos() * Math.max(0, getMaxScroll()) * 18f;
    }

    /** Vanilla only clears its scrollbar-drag flag on the NEXT click; the glass thumb reads it as "held" (see ScrollDragOwner). */
    @Override
    public void s1mp1e$endScrollDrag() { mouseClicked = false; }

    /** A click while the list is mid-glide snaps to the target row first (acts on the recipe drawn under the cursor). */
    @Inject(method = "mouseClicked", at = @At("HEAD"))
    private void s1mp1e$snapOnClick(double mx, double my, int button, CallbackInfoReturnable<Boolean> cir) {
        if (s1mp1e$sliding && s1mp1e$bar != null) {
            s1mp1e$bar.snapToTarget();
            s1mp1e$sliding = false;
        }
    }

    @Unique private StonecutterScreen self() { return (StonecutterScreen) (Object) this; }
}
