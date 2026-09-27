package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.module.HudGlass;
import dev.s1mp1e.glass.render.GlassProgram;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
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
        if (GlassProgram.ensureReady() && GlassProgram.usable()) {
            float af = (color >>> 24 & 0xFF) / 255.0F;   // fade timer lives in the colour's alpha byte
            int x = -width / 2;                          // vanilla draws the text at x = -width/2
            HudGlass.glassBoxCtx(ctx, x - 5, yOffset - 2, x + width + 5, yOffset + 11, 0.85F * af);
        } else {
            this.drawTextBackground(ctx, font, yOffset, width, color);
        }
    }
}
