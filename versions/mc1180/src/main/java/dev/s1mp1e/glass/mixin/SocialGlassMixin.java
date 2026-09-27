package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.gui.screen.multiplayer.SocialInteractionsScreen;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feature A — the social-interactions panel becomes ONE refracting liquid-glass plate (26.2's {@code SocialGlassMixin}
 * ported to the 1.18.2 MatrixStack family). The player rows / heads / ping bars / mute buttons and the small search
 * icon stay vanilla and on top.
 *
 * <p>1.20+ draws the panel with one {@code DrawContext.drawNineSlicedTexture}; 1.18.2's
 * {@code SocialInteractionsScreen.renderBackground} instead lays it as several {@code this.drawTexture(MatrixStack,
 * IIIIII)} blits (verified against yarn 1.18.2+build.4): one top edge {@code (left, 64, 236x8)}, N tiled middle rows
 * {@code (left, 72+16k, 236x16)}, one bottom edge {@code (left, .., 236x8)} — all width 236 — and finally the small
 * {@code 12x12} search icon at {@code u=243}. This mixin {@link Redirect}s every such blit: the wide panel pieces
 * (w &ge; 120) are swallowed while their bounding box is accumulated, and when the narrow search icon (w &lt; 120,
 * always drawn last) arrives it flushes ONE glass plate over that box first, then draws the icon on top. Gates on the
 * pipeline; unusable → the vanilla sprites are kept so the panel never vanishes.
 */
@Mixin(SocialInteractionsScreen.class)
public abstract class SocialGlassMixin {

    private int s1mp1e$x0, s1mp1e$y0, s1mp1e$x1, s1mp1e$y1;
    /** True once at least one wide panel piece has been accumulated this renderBackground call. */
    private boolean s1mp1e$has;

    @Inject(method = "renderBackground", at = @At("HEAD"))
    private void s1mp1e$reset(MatrixStack matrices, CallbackInfo ci) {
        s1mp1e$has = false;
    }

    @Redirect(method = "renderBackground",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screen/multiplayer/SocialInteractionsScreen;"
                            + "drawTexture(Lnet/minecraft/client/util/math/MatrixStack;IIIIII)V"))
    private void s1mp1e$panel(SocialInteractionsScreen self, MatrixStack matrices,
                              int x, int y, int u, int v, int w, int h) {
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) {
            self.drawTexture(matrices, x, y, u, v, w, h);   // glass off: vanilla sprite
            return;
        }
        if (w >= 120) {
            // A wide panel nine-slice piece: swallow it, grow the bounding box.
            if (!s1mp1e$has) {
                s1mp1e$x0 = x; s1mp1e$y0 = y; s1mp1e$x1 = x + w; s1mp1e$y1 = y + h; s1mp1e$has = true;
            } else {
                if (x < s1mp1e$x0) s1mp1e$x0 = x;
                if (y < s1mp1e$y0) s1mp1e$y0 = y;
                if (x + w > s1mp1e$x1) s1mp1e$x1 = x + w;
                if (y + h > s1mp1e$y1) s1mp1e$y1 = y + h;
            }
            return;
        }
        // The small search icon (drawn last): flush ONE glass plate over the whole panel, then the icon on top.
        if (s1mp1e$has) {
            // Frame-primary: super.renderBackground drew the dim before these blits, so grab a fresh world+dim
            // backdrop now (R4). The suppressed panel sprites are not in the framebuffer, so it never self-samples.
            SceneCapture.grabNow();
            GlassRenderer.panel(s1mp1e$x0, s1mp1e$y0, s1mp1e$x1, s1mp1e$y1, 1.0f);
            s1mp1e$has = false;
        }
        self.drawTexture(matrices, x, y, u, v, w, h);
    }
}
