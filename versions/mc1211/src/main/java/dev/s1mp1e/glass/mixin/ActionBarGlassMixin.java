package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.module.HudGlass;
import dev.s1mp1e.glass.render.GlassProgram;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The action-bar / item-pickup message becomes a liquid-glass pill (G5), matching the hotbar's
 * selected-item-name pill.
 *
 * <p>1.21.1 path: {@code InGameHud.renderOverlayMessage} draws the message with
 * {@code context.drawTextWithBackground(font, text, x, y, maxX, argb)} under a pose translated to the
 * screen centre; the backdrop is a plain dark rectangle. That call is redirected to a frosted glass
 * pill sized to the text, then the text is drawn on top with a shadow. The pill opacity tracks the
 * {@code argb} alpha, which already carries the message's fade-out timer, so the pill fades with the
 * text. Falls back to the vanilla {@code drawTextWithBackground} when the glass pipeline is not usable.
 */
@Mixin(InGameHud.class)
public abstract class ActionBarGlassMixin {

    @Redirect(
        method = "renderOverlayMessage",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/DrawContext;drawTextWithBackground(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/Text;IIII)I"
        )
    )
    private int s1mp1e$actionBarPill(DrawContext ctx, TextRenderer font, Text text, int x, int y, int maxX, int argb) {
        if (GlassProgram.ensureReady() && GlassProgram.usable()) {
            float af = (argb >>> 24 & 0xFF) / 255.0F;
            HudGlass.glassBoxCtx(ctx, x - 5, y - 2, x + maxX + 5, y + 11, 0.85F * af);
            return ctx.drawText(font, text, x, y, argb, true);
        }
        return ctx.drawTextWithBackground(font, text, x, y, maxX, argb);
    }
}
