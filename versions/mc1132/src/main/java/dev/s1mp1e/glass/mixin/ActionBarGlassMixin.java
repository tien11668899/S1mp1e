package dev.s1mp1e.glass.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.s1mp1e.client.module.HudGlass;
import dev.s1mp1e.glass.render.GlassProgram;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.hud.InGameHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
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
 */
@Mixin(InGameHud.class)
public abstract class ActionBarGlassMixin {

    @Shadow private String overlayMessage;

    @WrapOperation(method = "render",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/client/font/TextRenderer;method_18355(Ljava/lang/String;FFI)I"))
    private int s1mp1e$actionBarPill(TextRenderer font, String text, float x, float y, int color,
                                     Operation<Integer> original) {
        if (y == -4.0F && text != null && text == this.overlayMessage) {
            try {
                if (GlassProgram.ensureReady() && GlassProgram.usable()) {
                    int width = font.getStringWidth(text);
                    float af = ((color >>> 24) & 0xFF) / 255.0F;
                    HudGlass.glassBoxHotbar(-width / 2f - 5f, y - 2f, width / 2f + 5f, y + 11f, 0.85F * af);
                    com.mojang.blaze3d.platform.GlStateManager.enableBlend();   // what vanilla set up for the text
                    com.mojang.blaze3d.platform.GlStateManager.blendFuncSeparate(770, 771, 1, 0);
                }
            } catch (Throwable ignored) {
                // the message still draws
            }
        }
        return original.call(font, text, x, y, color);
    }
}
