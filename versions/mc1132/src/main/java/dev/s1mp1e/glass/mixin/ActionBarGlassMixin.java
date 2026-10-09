package dev.s1mp1e.glass.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.s1mp1e.client.module.HudGlass;
import dev.s1mp1e.glass.render.GlassProgram;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.hud.InGameHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

/**
 * The action-bar / item-pickup overlay message becomes a liquid-glass pill (G5), matching the hotbar's item-name pill.
 * 1.13.2 draws it inside {@code InGameHud.render} under a GL model-view translated to
 * {@code (scaledWidth/2, scaledHeight-68)} (via {@code GlStateManager.translatef}), with the dark backdrop drawn by the
 * private {@code method_19346(font, yOffset=-4, width, color)} and the text by a separate
 * {@code textRenderer.draw} (kept). That {@code method_19346} call (decompile-verified: 3 sites — action bar
 * {@code -4}, title {@code -10}, subtitle {@code 5}) is redirected: for the action bar ({@code yOffset == -4}) it
 * becomes a frosted glass pill (hotbar corner, R2) whose opacity tracks the message's fade (carried in the colour's
 * alpha byte); the title/subtitle backdrops and the glass-unavailable case fall through to the vanilla backdrop. The
 * pill is drawn at LOCAL coords straight under the active GL model-view (via {@link HudGlass#glassBoxHotbar}), so it
 * sits exactly where the text is — no MatrixStack projection is needed on this line.
 *
 * <p><b>1.13.2.</b> There is no text-background helper yet (the accessibility option that draws it is 1.14): the
 * action-bar message is one {@code TextRenderer.method_18355(overlayMessage, -w/2, -4, colour)} inside
 * {@code InGameHud.render}. That draw is wrapped: the glass pill is laid first (same rectangle the 1.14+ helper
 * covers), its alpha is the alpha byte vanilla has just put into the text colour.
 *
 * <p><b>Appear fade (2026-10-08, ported from mc1144).</b> Vanilla fades the message OUT (via {@code overlayRemaining},
 * already baked into the colour's alpha byte) but pops it IN. Fade it IN over 150&nbsp;ms from a hidden&rarr;shown edge,
 * keyed on the gap since the last action-bar draw (NOT on {@code setOverlayMessage}: servers re-send the same bar each
 * tick and countdowns change its text, neither of which may restart the fade or it would flicker). 1.13.2 draws the
 * background AND the text in this ONE {@code method_18355} wrap, so multiplying both the pill alpha and the text colour's
 * alpha by the same appear factor fades them in together — no second redirect is needed (unlike mc1144, where 1.14.4
 * splits the background and text into two calls). Only the action-bar branch ({@code y == -4}) touches the clock, so the
 * title / subtitle never restart it.
 */
@Mixin(InGameHud.class)
public abstract class ActionBarGlassMixin {

    @Shadow private String overlayMessage;

    /** Appear fade length, matching the glass screen-open fade. */
    @Unique private static final float S1MP1E$FADE_S = 0.15F;
    /** Not drawn for longer than this = hidden, so the next draw is a fresh appearance. */
    @Unique private static final long S1MP1E$GONE_NS = 250_000_000L;
    @Unique private static long s1mp1e$shownAt, s1mp1e$lastDrawn;

    /**
     * Fade in the action bar over 150 ms from a hidden -> shown edge, keyed on the gap since the last draw (not on
     * {@code setOverlayMessage}). Returns the appear factor in [0,1]. Only the action-bar wrap calls it, so the title /
     * subtitle never restart it.
     */
    @Unique
    private static float s1mp1e$appear() {
        long now = System.nanoTime();
        if (now - s1mp1e$lastDrawn > S1MP1E$GONE_NS) s1mp1e$shownAt = now;
        s1mp1e$lastDrawn = now;
        float t = (now - s1mp1e$shownAt) / 1.0e9F / S1MP1E$FADE_S;
        return t >= 1F ? 1F : Math.max(0F, t);
    }

    @WrapOperation(method = "render",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/client/font/TextRenderer;method_18355(Ljava/lang/String;FFI)I"))
    private int s1mp1e$actionBarPill(TextRenderer font, String text, float x, float y, int color,
                                     Operation<Integer> original) {
        if (y == -4.0F && text != null && text == this.overlayMessage) {
            float appear = s1mp1e$appear();   // vanilla OUT fade (in colour alpha) * appear IN fade (150 ms)
            try {
                if (GlassProgram.ensureReady() && GlassProgram.usable()) {
                    int width = font.getStringWidth(text);
                    float af = ((color >>> 24) & 0xFF) / 255.0F;
                    HudGlass.glassBoxHotbar(-width / 2f - 5f, y - 2f, width / 2f + 5f, y + 11f, 0.85F * af * appear);
                    GlStateManager.enableBlend();   // what vanilla set up for the text
                    GlStateManager.blendFuncSeparate(770, 771, 1, 0);
                }
            } catch (Throwable ignored) {
                // the message still draws
            }
            int a = Math.round(((color >>> 24) & 0xFF) * appear) & 0xFF;   // text fades in with the pill
            return original.call(font, text, x, y, a << 24 | color & 0xFFFFFF);
        }
        return original.call(font, text, x, y, color);
    }
}
