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
 * 1.16.5 draws it inside {@code InGameHud.render} under a GL model-view translated to
 * {@code (scaledWidth/2, scaledHeight-68)} (via {@code RenderSystem.translatef}), with the dark backdrop drawn by the
 * private {@code drawTextBackground(matrices, font, yOffset=-4, width, color)} and the text by a separate
 * {@code textRenderer.draw} (kept). That {@code drawTextBackground} call (decompile-verified: 3 sites — action bar
 * {@code -4}, title {@code -10}, subtitle {@code 5}) is redirected: for the action bar ({@code yOffset == -4}) it
 * becomes a frosted glass pill (hotbar corner, R2) whose opacity tracks the message's fade (carried in the colour's
 * alpha byte); the title/subtitle backdrops and the glass-unavailable case fall through to the vanilla backdrop. The
 * pill is drawn at LOCAL coords straight under the active GL model-view (via {@link HudGlass#glassBoxHotbar}), so it
 * sits exactly where the text is — no MatrixStack projection is needed on this line.
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
            HudGlass.glassBoxHotbar(-width / 2f - 5f, yOffset - 2f, width / 2f + 5f, yOffset + 11f, 0.85F * af);
        } else {
            this.drawTextBackground(matrices, font, yOffset, width, color);
        }
    }
}
