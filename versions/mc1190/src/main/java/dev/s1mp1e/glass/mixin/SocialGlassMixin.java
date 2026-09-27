package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.GlassCorners;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.gui.screen.multiplayer.SocialInteractionsScreen;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Feature A — the Social Interactions panel becomes one refracting liquid-glass plate + grey readability scrim, the
 * 26.2 / 1.20.1 {@code SocialGlassMixin} adapted to the 1.19.2 [MatrixStack] fixed-seam family.
 *
 * <p>Verified against yarn 1.19.2+build.28: {@code SocialInteractionsScreen.renderBackground(MatrixStack)} first calls
 * {@code super.renderBackground} (the dim over the paused world) and then composes the panel from three
 * {@code SOCIAL_INTERACTIONS_TEXTURE} blits — all at {@code x = getSearchBoxX()+3}, {@code width 236}: a top piece
 * ({@code u=1 v=1 h=8} at {@code y=64}), {@code rowCount} middle pieces ({@code u=1 v=10 h=16}) and a bottom piece
 * ({@code u=1 v=27 h=8}) — plus one search-icon sprite ({@code u=243 v=1}, 12x12). This {@link Redirect} drops the three
 * {@code u==1} panel pieces and, on the bottom ({@code v==27}) one — where the whole panel rect is known
 * ({@code x .. x+236}, top {@code 64} .. {@code y+8}) — grabs a FRESH backdrop (R4: the panel texture is suppressed, so
 * only world+dim is in the framebuffer) and paints a hotbar-corner-radius glass plate (R2) with a grey scrim. The search
 * icon, player rows, heads, ping bars, mute buttons and the tab buttons all draw afterwards and stay on top. Falls back
 * to the vanilla texture whenever the glass pipeline is unavailable.
 */
@Mixin(SocialInteractionsScreen.class)
public abstract class SocialGlassMixin {

    @Redirect(method = "renderBackground",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screen/multiplayer/SocialInteractionsScreen;"
                            + "drawTexture(Lnet/minecraft/client/util/math/MatrixStack;IIIIII)V"))
    private void s1mp1e$glassPanel(SocialInteractionsScreen self, MatrixStack matrices,
                                   int x, int y, int u, int v, int w, int h) {
        // u==243 is the magnifying-glass search icon (a separate sprite): keep it vanilla, on top of the glass.
        // Any state where the glass pipeline is down -> draw everything vanilla (safe fallback).
        if (u != 1 || !GlassProgram.ensureReady() || !GlassProgram.usable()) {
            self.drawTexture(matrices, x, y, u, v, w, h);
            return;
        }
        // u==1 panel-background pieces (top v=1, middle v=10 x rowCount, bottom v=27): suppress the vanilla blit.
        // On the bottom piece the entire panel geometry is known, so paint the one glass plate then.
        if (v == 27) {
            int x0 = x, y0 = 64, x1 = x + w, y1 = y + h;   // panel top is drawn at y=64; bottom piece ends at y+h
            SceneCapture.grabNow();                        // R4 — fresh world+dim backdrop, panel not yet drawn
            float corner = GlassCorners.knob(x1 - x0, y1 - y0);   // R2 — hotbar corner radius for this NEW piece
            GlassRenderer.glass(x0, y0, x1, y1, GlassRenderer.PAD_PANEL, corner, 0f, 1f, GlassRenderer.FROST_PANEL);
            if (GlassProgram.roundUsable())
                GlassRenderer.roundRect(x0 + 3, y0 + 3, x1 - 3, y1 - 3, GlassCorners.HOTBAR_RADIUS, 0x99101018);
        }
        // v==1 / v==10 panel pieces: nothing to draw — the v==27 plate already covers them.
    }
}
