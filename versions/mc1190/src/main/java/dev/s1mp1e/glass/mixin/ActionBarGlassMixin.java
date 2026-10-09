package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.module.HudGlass;
import dev.s1mp1e.glass.render.GlassProgram;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The action-bar / item-pickup overlay message becomes a liquid-glass pill (G5), matching the hotbar's item-name pill.
 * 1.19.2 draws it inside {@code InGameHud.render} under a pose translated to {@code (scaledWidth/2, scaledHeight-68)},
 * with the dark backdrop drawn by the private {@code drawTextBackground(matrices, font, yOffset=-4, width, color)} and
 * the text by a separate {@code TextRenderer.drawWithShadow} (kept). That {@code drawTextBackground} call is redirected:
 * for the action bar ({@code yOffset == -4}) it becomes a frosted glass pill (hotbar corner, R2) whose opacity tracks
 * the message's fade (carried in the colour's alpha byte); the title/subtitle backdrops ({@code yOffset -10 / other})
 * and the glass-unavailable case fall through to the vanilla backdrop. The pill is projected through the current pose
 * so it sits exactly where the text is.
 *
 * <p><b>Appear fade (2026-10-08).</b> Vanilla fades the message OUT (via the alpha byte of {@code color}) but pops it
 * IN. Fade it IN over 150&nbsp;ms from a hidden&rarr;shown edge, keyed on the gap since the last draw (NOT on
 * {@code setOverlayMessage}: servers re-send the same bar each tick and countdowns change its text, neither of which
 * may restart the fade or it would flicker). The background and text are TWO separate calls in 1.19.2 (unlike
 * 1.21.1's combined {@code drawTextWithBackground}), so both the pill redirect and a second redirect on the action-bar
 * text ({@code drawWithShadow} ordinal&nbsp;0 in {@code render}, verified by javap on the 1.19.2 jar) run the same
 * appear curve, or the pill would fade while the text popped in.
 */
@Mixin(InGameHud.class)
public abstract class ActionBarGlassMixin {

    @Shadow private void drawTextBackground(MatrixStack matrices, TextRenderer textRenderer, int yOffset, int width, int color) {}

    /** Appear fade length, matching the glass screen-open fade. */
    @Unique private static final float S1MP1E$FADE_S = 0.15F;
    /** Not drawn for longer than this = hidden, so the next draw is a fresh appearance. */
    @Unique private static final long S1MP1E$GONE_NS = 250_000_000L;
    @Unique private static long s1mp1e$shownAt, s1mp1e$lastDrawn;

    /**
     * Fade in the action bar over 150 ms from a hidden -> shown edge. Keyed on the gap since the last draw, not on
     * {@code setOverlayMessage}. Only the action-bar call sites touch this clock (the pill redirect gates on
     * {@code yOffset == -4}; the text redirect is ordinal 0), so the title / subtitle never restart it.
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

    @Redirect(method = "render",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/client/gui/hud/InGameHud;drawTextBackground(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/font/TextRenderer;III)V"))
    private void s1mp1e$actionBarPill(InGameHud self, MatrixStack matrices, TextRenderer font, int yOffset, int width, int color) {
        if (yOffset != -4) {                                  // title / subtitle backdrops: untouched
            this.drawTextBackground(matrices, font, yOffset, width, color);
            return;
        }
        color = s1mp1e$appear(color);                         // vanilla pops the bar IN; fade it in over 150 ms
        if ((color >>> 24 & 0xFF) < 4) return;                // nothing visible yet this frame
        if (GlassProgram.ensureReady() && GlassProgram.usable()) {
            float af = ((color >>> 24) & 0xFF) / 255.0F;      // fade timer (and the appear fade) live in the alpha byte
            HudGlass.glassBoxLocalHotbar(matrices, -width / 2f - 5f, yOffset - 2f, width / 2f + 5f, yOffset + 11f,
                                         0.85F * af);
        } else {
            this.drawTextBackground(matrices, font, yOffset, width, color);
        }
    }

    /**
     * Fade the action-bar TEXT with the same appear curve. {@code drawWithShadow} ordinal 0 in {@code render} is the
     * action bar (ordinals 1/2 are title/subtitle). Calling {@code s1mp1e$appear} a second time in the same frame is
     * safe: the gap since the background call is far under {@code GONE_NS}, so it never restarts the fade and returns
     * the same alpha the pill used.
     */
    @Redirect(method = "render",
              at = @At(value = "INVOKE", ordinal = 0,
                       target = "Lnet/minecraft/client/font/TextRenderer;drawWithShadow(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/text/Text;FFI)I"))
    private int s1mp1e$actionBarText(TextRenderer font, MatrixStack matrices, Text text, float x, float y, int color) {
        return font.drawWithShadow(matrices, text, x, y, s1mp1e$appear(color));
    }
}
