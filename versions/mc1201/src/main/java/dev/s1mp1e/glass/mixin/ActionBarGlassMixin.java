package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.module.HudGlass;
import dev.s1mp1e.glass.render.GlassProgram;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The action-bar / item-pickup message becomes a liquid-glass pill (G5), matching the hotbar's
 * selected-item-name pill.
 *
 * <p>1.20.1 path (verified by decompile): there is no {@code DrawContext.drawTextWithBackground} in
 * 1.20.1. {@code InGameHud.render} draws the overlay message with a private helper
 * {@code drawTextBackground(DrawContext, TextRenderer, int yOffset, int width, int color)} (which fills
 * a dark rectangle behind the text) then {@code drawTextWithShadow} for the text, under a pose
 * translated to {@code (scaledWidth/2, scaledHeight-68)}. The same helper is reused for the title and
 * subtitle, so we redirect only its FIRST call site in {@code render} (ordinal 0 = the action bar) to a
 * frosted glass pill sized to the text; the vanilla {@code drawTextWithShadow} then draws the text on
 * top. The pill opacity tracks the {@code color} alpha, which already carries the message's fade-out
 * timer, so the pill fades with the text. Falls back to the vanilla {@code drawTextBackground} when the
 * glass pipeline is not usable. Title / subtitle keep their vanilla backdrops.
 */
@Mixin(InGameHud.class)
public abstract class ActionBarGlassMixin {

    @Shadow private void drawTextBackground(DrawContext context, TextRenderer textRenderer,
                                            int yOffset, int width, int color) {}

    /** Appear fade length, matching the glass screen-open fade. */
    @Unique private static final float S1MP1E$FADE_S = 0.15F;
    /** Not drawn for longer than this = hidden, so the next draw is a fresh appearance. */
    @Unique private static final long S1MP1E$GONE_NS = 250_000_000L;
    @Unique private static long s1mp1e$shownAt, s1mp1e$lastDrawn;

    /**
     * Vanilla fades the message OUT (the alpha in {@code color}) but pops it IN. Fade in over 150 ms from a hidden →
     * shown edge. Keyed on the gap since the last draw, not on {@code setOverlayMessage}: servers re-send the same bar
     * every tick and countdowns change its text, and neither may restart the fade (that would flicker). Only the
     * action-bar call site (ordinal 0) is redirected here, so the title / subtitle never touch this clock.
     */
    @Unique
    private static int s1mp1e$appear(int argb) {
        long now = System.nanoTime();
        if (now - s1mp1e$lastDrawn > S1MP1E$GONE_NS) s1mp1e$shownAt = now;
        s1mp1e$lastDrawn = now;
        float t = (now - s1mp1e$shownAt) / 1.0e9F / S1MP1E$FADE_S;
        if (t >= 1F) return argb;
        int a = Math.round((argb >>> 24 & 0xFF) * Math.max(0F, t)) & 0xFF;
        return a << 24 | argb & 0xFFFFFF;
    }

    @Redirect(
        method = "render",
        at = @At(
            value = "INVOKE",
            ordinal = 0,
            target = "Lnet/minecraft/client/gui/hud/InGameHud;drawTextBackground(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/font/TextRenderer;III)V"
        )
    )
    private void s1mp1e$actionBarPill(InGameHud self, DrawContext ctx, TextRenderer font,
                                      int yOffset, int width, int color) {
        color = s1mp1e$appear(color);                    // vanilla pops the bar IN; fade it in over 150 ms
        if ((color >>> 24 & 0xFF) < 4) return;           // nothing visible yet this frame
        if (GlassProgram.ensureReady() && GlassProgram.usable()) {
            float af = (color >>> 24 & 0xFF) / 255.0F;   // fade timer (and the appear fade) live in the colour's alpha byte
            int x = -width / 2;                          // vanilla draws the text at x = -width/2
            HudGlass.glassBoxCtx(ctx, x - 5, yOffset - 2, x + width + 5, yOffset + 11, 0.85F * af);
        } else {
            this.drawTextBackground(ctx, font, yOffset, width, color);
        }
    }

    /**
     * 1.20.1 draws the action-bar TEXT in a separate call from its background (unlike 1.21.1's combined
     * {@code drawTextWithBackground}), so fade the text with the same appear curve or it would pop in while the pill
     * fades. Ordinal 0 {@code drawTextWithShadow} in {@code render} is the action bar (ordinals 1/2 are title/subtitle).
     * Calling {@code s1mp1e$appear} a second time in the same frame is safe: the gap since the background call is far
     * under {@code GONE_NS}, so it never restarts the fade and returns the same alpha the pill used.
     */
    @Redirect(
        method = "render",
        at = @At(
            value = "INVOKE",
            ordinal = 0,
            target = "Lnet/minecraft/client/gui/DrawContext;drawTextWithShadow(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/Text;III)I"
        )
    )
    private int s1mp1e$actionBarText(DrawContext ctx, TextRenderer font, Text text, int x, int y, int color) {
        return ctx.drawTextWithShadow(font, text, x, y, s1mp1e$appear(color));
    }
}
