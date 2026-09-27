package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.module.HudGlass;
import dev.s1mp1e.glass.render.GlassProgram;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.util.math.MathHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The action-bar / item-pickup overlay message becomes a liquid-glass pill (G5), matching the hotbar's item-name pill.
 * 1.15.2 draws it inside {@code InGameHud.render(float)} under a GL model-view translated to
 * {@code (scaledWidth/2, scaledHeight-68)} (via {@code RenderSystem.translatef}), with the dark backdrop drawn by the
 * private {@code drawTextBackground(TextRenderer, int yOffset, int width)} and the text by a separate
 * {@code textRenderer.draw} (kept). That {@code drawTextBackground} has THREE call sites in render (action bar
 * {@code yOffset -4}, title, subtitle — javap-verified); the FIRST (ordinal 0 = action bar) is redirected to a frosted
 * glass pill (hotbar corner, R2) whose opacity tracks the message's fade (from {@code overlayRemaining}), and the
 * title/subtitle backdrops and the glass-unavailable case fall through to the vanilla backdrop. The pill is drawn at
 * LOCAL coords straight under the active GL model-view (via {@link HudGlass#glassBoxHotbar}), so it sits exactly where
 * the text is.
 *
 * <p>1.15.2 delta vs 1.16.5: {@code drawTextBackground} has no {@code MatrixStack} and no {@code color} arg (it reads
 * {@code getTextBackgroundColor} itself), so the fade cannot be read off a colour byte — it is taken from the
 * {@code overlayRemaining} field the same way vanilla fades the action-bar TEXT ({@code alpha = clamp(remaining/20)}).
 */
@Mixin(InGameHud.class)
public abstract class ActionBarGlassMixin {

    @Shadow private int overlayRemaining;

    @Shadow private void drawTextBackground(TextRenderer textRenderer, int yOffset, int width) {}

    @Redirect(method = "render",
              at = @At(value = "INVOKE", ordinal = 0,
                       target = "Lnet/minecraft/client/gui/hud/InGameHud;drawTextBackground(Lnet/minecraft/client/font/TextRenderer;II)V"))
    private void s1mp1e$actionBarPill(InGameHud self, TextRenderer font, int yOffset, int width) {
        if (GlassProgram.ensureReady() && GlassProgram.usable()) {
            float af = MathHelper.clamp(this.overlayRemaining / 20.0F, 0.0F, 1.0F);
            HudGlass.glassBoxHotbar(-width / 2f - 5f, yOffset - 2f, width / 2f + 5f, yOffset + 11f, 0.85F * af);
        } else {
            this.drawTextBackground(font, yOffset, width);
        }
    }
}
