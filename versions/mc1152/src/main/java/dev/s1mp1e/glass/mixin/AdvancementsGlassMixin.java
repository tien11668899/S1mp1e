package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.ScreenOpenFade;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.advancement.AdvancementsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * PORT_SPEC feature (A) — the advancements window is framed in liquid glass: the ornate
 * {@code WINDOW_TEXTURE} wooden frame is dropped and a single refracting panel is laid behind the
 * whole {@code 252 x 140} window, so the advancement tree sits on frosted glass with a clean glass
 * border. The 1.15.2 Fabric counterpart of 26.2's {@code AdvancementsGlassMixin}.
 *
 * <p>Order (verified against the mapped 1.15.2 jar). {@code AdvancementsScreen.render} runs
 * {@code renderBackground()} (world dim / menu blur), then {@code drawAdvancementTree(mouseX,
 * mouseY, x, y)}, then {@code drawWidgets(x, y)}, then {@code drawWidgetTooltip(...)}. So:
 * <ol>
 *   <li>{@link #s1mp1e$advPanel} injects the glass panel at {@code drawAdvancementTree} HEAD — behind
 *       the tree interior and the frame. It spans the full {@code 252 x 140} window at the window
 *       origin {@code (x, y)} the render passed in.</li>
 *   <li>The selected tab's own tiled-texture interior then paints over the panel in the interior,
 *       keeping the coloured advancement lines / icons readable on a dark backing (LOOK SPEC: a
 *       scrim under content). The result is the tree framed by glass at the border and title bar.</li>
 *   <li>{@link #s1mp1e$advFrame} drops the {@code blit(x, y, 0, 0, 252, 140)} window frame (the first
 *       and only {@code AbstractInventoryScreen.blit(IIIIII)} in {@code drawWidgets}) so the glass
 *       border shows; the tab backgrounds / icons are drawn on {@code AdvancementTab} (a different
 *       owner) and are untouched.</li>
 * </ol>
 * The tabs and the advancement tooltip stay vanilla (readable as-is); only the window frame changes.
 *
 * <p>The advancements screen is opened in-world and is NOT a {@code ContainerScreen}, so
 * {@code ScreenMenuBackdropMixin} blurs the world behind it; the panel refracts that blurred+dimmed
 * composite. A fresh {@code grabNow()} at the panel draw keeps the backdrop deterministic every
 * frame (rule R4). Panel material / radius = the container-panel family ({@link GlassRenderer#panel})
 * — this is a plate, not a hotbar-corner piece (§2A), so it keeps the panel radius.
 */
@Mixin(AdvancementsScreen.class)
public abstract class AdvancementsGlassMixin {

    /** The window texture region drawn by {@code drawWidgets} (literal 252 x 140 in the vanilla blit). */
    private static final int WINDOW_W = 252;
    private static final int WINDOW_H = 140;

    @Inject(method = "drawAdvancementTree(IIII)V", at = @At("HEAD"))
    private void s1mp1e$advPanel(int mouseX, int mouseY, int x, int y, CallbackInfo ci) {
        try {
            if (!(GlassProgram.ensureReady() && GlassProgram.usable())) return;
            // Frame-primary surface: grab a fresh world+dim+blur backdrop this frame (R4).
            SceneCapture.grabNow();
            float fade = ScreenOpenFade.value(MinecraftClient.getInstance().currentScreen);
            GlassRenderer.panel(x, y, x + WINDOW_W, y + WINDOW_H, fade);
        } catch (Throwable ignored) {
            // a failed panel leaves the vanilla frame (never dropped below when glass is down)
        }
    }

    @Redirect(method = "drawWidgets(II)V",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screen/advancement/AdvancementsScreen;blit(IIIIII)V"))
    private void s1mp1e$advFrame(AdvancementsScreen self, int x, int y, int u, int v, int w, int h) {
        // Drop the wooden window frame (252 x 140) when glass is up — the injected panel shows through
        // at the border. Any other blit(IIIIII) on the screen, or glass being down, draws vanilla.
        boolean isWindow = (u == 0 && v == 0 && w == WINDOW_W && h == WINDOW_H);
        if (isWindow && GlassProgram.ensureReady() && GlassProgram.usable()) return;
        self.blit(x, y, u, v, w, h);
    }

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
}
