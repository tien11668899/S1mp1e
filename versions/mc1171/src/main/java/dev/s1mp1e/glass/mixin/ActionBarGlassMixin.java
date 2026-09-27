package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.module.HudGlass;
import dev.s1mp1e.glass.render.GlassProgram;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The action-bar / item-pickup overlay message becomes a liquid-glass pill (G5), matching the hotbar's item-name pill.
 * 1.17.1 draws it inside {@code InGameHud.render} under a pose translated to {@code (scaledWidth/2, scaledHeight-68)},
 * with the dark backdrop drawn by the private {@code drawTextBackground(matrices, font, yOffset=-4, width, color)} and
 * the text by a separate {@code drawWithShadow} (kept). That {@code drawTextBackground} call (javap-verified: 3 INVOKEs
 * in {@code render}, identical to the 1.19.2 sibling) is redirected: for the action bar ({@code yOffset == -4}) it
 * becomes a frosted glass pill (hotbar corner, R2) whose opacity tracks the message's fade (carried in the colour's
 * alpha byte); the title/subtitle backdrops ({@code yOffset -10 / other}) and the glass-unavailable case fall through
 * to the vanilla backdrop. The pill is projected through the current pose so it sits exactly where the text is.
 */
@Mixin(InGameHud.class)
public abstract class ActionBarGlassMixin {

    @Shadow private void drawTextBackground(MatrixStack matrices, TextRenderer textRenderer, int yOffset, int width, int color) {}

    @Redirect(method = "render",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/client/gui/hud/InGameHud;drawTextBackground(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/font/TextRenderer;III)V"))
    private void s1mp1e$actionBarPill(InGameHud self, MatrixStack matrices, TextRenderer font, int yOffset, int width, int color) {
        if (yOffset == -4 && GlassProgram.ensureReady() && GlassProgram.usable()) {
            float af = ((color >>> 24) & 0xFF) / 255.0F;
            HudGlass.glassBoxLocalHotbar(matrices, -width / 2f - 5f, yOffset - 2f, width / 2f + 5f, yOffset + 11f,
                                         0.85F * af);
        } else {
            this.drawTextBackground(matrices, font, yOffset, width, color);
        }
    }
}
