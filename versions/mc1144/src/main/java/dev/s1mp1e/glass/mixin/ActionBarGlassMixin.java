package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.module.HudGlass;
import dev.s1mp1e.glass.render.GlassProgram;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.util.math.MathHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The action-bar / item-pickup overlay message becomes a liquid-glass pill (G5), matching the hotbar's item-name pill.
 * 1.14.4 draws it inside {@code InGameHud.render} under a GL model-view translated to
 * {@code (scaledWidth/2, scaledHeight-68)} (via {@code GlStateManager.translatef}), with the dark backdrop drawn by the
 * private {@code method_19346(font, yOffset, width)} and the text by a separate {@code textRenderer.draw} (kept).
 * {@code method_19346} has THREE call sites in render (javap-verified on the merged jar: action bar {@code -4} at
 * offset 806, title {@code -10} at 1068, subtitle {@code 5} at 1132); the FIRST (ordinal 0 = action bar) is redirected
 * to a frosted glass pill (hotbar corner, R2) whose opacity tracks the message's fade; the title / subtitle backdrops
 * and the glass-unavailable case fall through to the vanilla backdrop. The pill is drawn at LOCAL coords straight under
 * the active GL model-view (via {@link HudGlass#glassBoxHotbar}), so it sits exactly where the text is.
 *
 * <p><b>Appear fade (2026-10-08, ported from mc1152).</b> Vanilla fades the message OUT (via {@code overlayRemaining})
 * but pops it IN. Fade it IN over 150&nbsp;ms from a hidden&rarr;shown edge, keyed on the gap since the last draw (NOT
 * on {@code setOverlayMessage}: servers re-send the same bar each tick and countdowns change its text, neither of
 * which may restart the fade or it would flicker). The background and the text are TWO separate calls in 1.14.4, so
 * both the pill redirect and a second redirect on the action-bar text run the same appear curve, or the pill would
 * fade while the text popped in.
 *
 * <p><b>1.14.4 gotcha (javap-verified).</b> The action-bar TEXT is the first {@code TextRenderer.draw(String,F,F,I)} in
 * render (offset 834, ordinal&nbsp;0) — the title / subtitle use {@code drawWithShadow}, so {@code draw} ordinal 0 is
 * unambiguously the action bar. {@code method_19346} takes no colour, so the OUT fade is read off {@code overlayRemaining}
 * the same way vanilla fades the TEXT: {@code alpha = clamp((overlayRemaining - tickDelta) / 20)}.
 */
@Mixin(InGameHud.class)
public abstract class ActionBarGlassMixin {

    @Shadow private void method_19346(TextRenderer textRenderer, int yOffset, int width) {}

    @Shadow private int overlayRemaining;

    /** Appear fade length, matching the glass screen-open fade. */
    @Unique private static final float S1MP1E$FADE_S = 0.15F;
    /** Not drawn for longer than this = hidden, so the next draw is a fresh appearance. */
    @Unique private static final long S1MP1E$GONE_NS = 250_000_000L;
    @Unique private static long s1mp1e$shownAt, s1mp1e$lastDrawn;

    /**
     * Fade in the action bar over 150 ms from a hidden -> shown edge, keyed on the gap since the last draw (not on
     * {@code setOverlayMessage}). Returns the appear factor in [0,1]. Only the action-bar call sites (pill ordinal 0,
     * text ordinal 0) touch this clock, so the title / subtitle never restart it. Calling it twice in the same frame
     * (pill then text) is safe: the second call's gap is far under {@code GONE_NS}, so it returns the same factor.
     */
    @Unique
    private static float s1mp1e$appear() {
        long now = System.nanoTime();
        if (now - s1mp1e$lastDrawn > S1MP1E$GONE_NS) s1mp1e$shownAt = now;
        s1mp1e$lastDrawn = now;
        float t = (now - s1mp1e$shownAt) / 1.0e9F / S1MP1E$FADE_S;
        return t >= 1F ? 1F : Math.max(0F, t);
    }

    @Redirect(method = "render",
              at = @At(value = "INVOKE", ordinal = 0,
                       target = "Lnet/minecraft/client/gui/hud/InGameHud;method_19346(Lnet/minecraft/client/font/TextRenderer;II)V"))
    private void s1mp1e$actionBarPill(InGameHud self, TextRenderer font, int yOffset, int width) {
        if (GlassProgram.ensureReady() && GlassProgram.usable()) {
            // vanilla OUT fade (overlayRemaining - tickDelta, clamped to [0,1]) * appear IN fade (150 ms)
            float out = MathHelper.clamp(
                    (this.overlayRemaining - MinecraftClient.getInstance().getTickDelta()) / 20.0F, 0.0F, 1.0F);
            float af = out * s1mp1e$appear();
            HudGlass.glassBoxHotbar(-width / 2f - 5f, yOffset - 2f, width / 2f + 5f, yOffset + 11f, 0.85F * af);
        } else {
            this.method_19346(font, yOffset, width);
        }
    }

    /**
     * Fade the action-bar TEXT with the same appear curve. The text's {@code color} alpha byte already carries the
     * vanilla OUT fade; multiply it by the appear factor so the text fades IN together with the pill.
     */
    @Redirect(method = "render",
              at = @At(value = "INVOKE", ordinal = 0,
                       target = "Lnet/minecraft/client/font/TextRenderer;draw(Ljava/lang/String;FFI)I"))
    private int s1mp1e$actionBarText(TextRenderer font, String text, float x, float y, int color) {
        int a = Math.round((color >>> 24 & 0xFF) * s1mp1e$appear()) & 0xFF;
        return font.draw(text, x, y, a << 24 | color & 0xFFFFFF);
    }
}
