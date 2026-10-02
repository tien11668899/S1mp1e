package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.ScreenOpenFade;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.advancement.AdvancementsScreen;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feature (A) — the advancements window: the wooden frame becomes a liquid-glass plate and the tree is
 * framed in glass. The 1.16.5 Fabric port of LiquidGlass26's {@code AdvancementsGlassMixin}.
 *
 * <h3>1.16.5 vanilla shape (javap-verified, merged jar)</h3>
 * {@code AdvancementsScreen.render} computes {@code i = (width-252)/2}, {@code j = (height-140)/2}, then
 * {@code renderBackground} → {@code drawAdvancementTree(matrices, mx, my, i, j)} (the selected tab's
 * scissored tree, over its own OPAQUE tiled background) → {@code drawWidgets(matrices, i, j)} (the
 * {@code WINDOW_TEXTURE} wooden frame + tab icons + title) → {@code drawWidgetTooltip}.
 *
 * <p>This mixin:
 * <ul>
 *   <li>at {@code drawAdvancementTree} HEAD (after the world dim, BEFORE the tree — and BEFORE the tab's
 *       own scissor is set) enqueues one frosted glass plate over the whole {@code 252 x 140} window at
 *       {@code (i, j)} in the container-panel material. The tab's opaque tiled tree background then paints
 *       over the plate's centre, so glass shows only in the ~9 px frame border + title bar = "tree framed
 *       in glass". Drawn unscissored so it is not clipped to the tree interior;</li>
 *   <li>redirects the {@code WINDOW_TEXTURE} blit in {@code drawWidgets} (the sole
 *       {@code AdvancementsScreen.drawTexture}, ordinal 0) to a no-op, dropping the wooden frame. Tab
 *       icons ({@code AdvancementTab}) and the title text are separate draws and survive.</li>
 * </ul>
 *
 * <p>The plate is FRAME-PRIMARY, so it takes a fresh {@link SceneCapture#grabNow()} (R4). Both hooks gate
 * on {@link GlassProgram#usable()} (stable within a frame), so when glass is down the panel is skipped
 * AND the vanilla frame is kept — the screen never loses its border.
 */
@Mixin(AdvancementsScreen.class)
public abstract class AdvancementsGlassMixin {

    @org.spongepowered.asm.mixin.Shadow @org.spongepowered.asm.mixin.Final
    private java.util.Map<net.minecraft.advancement.Advancement,
            net.minecraft.client.gui.screen.advancement.AdvancementTab> tabs;
    @org.spongepowered.asm.mixin.Shadow
    private net.minecraft.client.gui.screen.advancement.AdvancementTab selectedTab;

    /**
     * Switching advancement tab swaps the whole tree in one frame. Snapshot the outgoing frame and cross-dissolve it
     * over the new tree (the glass window frame and tab row overlap, so only the tree region visibly cross-fades). HEAD,
     * before {@code selectedTab} flips. Skips a no-op re-select of the current tab. (Tabs are keyed by
     * {@code Advancement} here, not 1.20.2+'s {@code AdvancementEntry}.)
     */
    @Inject(method = "selectTab", at = @At("HEAD"))
    private void s1mp1e$dissolveAdvTab(net.minecraft.advancement.Advancement advancement, CallbackInfo ci) {
        net.minecraft.client.gui.screen.advancement.AdvancementTab next =
                advancement == null ? null : this.tabs.get(advancement);
        if (next != null && this.selectedTab != null && next != this.selectedTab) {
            dev.s1mp1e.glass.render.ScreenDissolve.onTabSwitch();
        }
    }

    /** Vanilla advancement window size (the {@code WINDOW_TEXTURE} blit is {@code 252 x 140}). */
    @Unique private static final int WIN_W = 252;
    @Unique private static final int WIN_H = 140;

    @Inject(method = "drawAdvancementTree", at = @At("HEAD"))
    private void s1mp1e$glassWindow(MatrixStack matrices, int mouseX, int mouseY, int x, int y,
                                    CallbackInfo ci) {
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) return;
        // The shared 150 ms screen-open ramp (the same one the glass buttons use), like 26.2's ScreenOpenFade.
        float fade = ScreenOpenFade.value(MinecraftClient.getInstance().currentScreen);

        // FRAME-PRIMARY surface: fresh backdrop (world + dim) so the plate never folds onto a stale
        // grab and flickers at high fps (R4).
        SceneCapture.grabNow();
        GlassRenderer.panel(x, y, x + WIN_W, y + WIN_H, fade);
    }

    @Redirect(method = "drawWidgets",
            at = @At(value = "INVOKE", ordinal = 0,
                     target = "Lnet/minecraft/client/gui/screen/advancement/AdvancementsScreen;"
                            + "drawTexture(Lnet/minecraft/client/util/math/MatrixStack;IIIIII)V"))
    private void s1mp1e$dropFrame(AdvancementsScreen self, MatrixStack matrices,
                                  int x, int y, int u, int v, int w, int h) {
        if (GlassProgram.ensureReady() && GlassProgram.usable()) return;   // glass plate replaced it
        self.drawTexture(matrices, x, y, u, v, w, h);
    }
}
