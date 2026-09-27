package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.module.HudGlass;
import dev.s1mp1e.glass.render.GlassProgram;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The open chat input field (G1, second half) becomes a liquid-glass bar. 1.16.5's {@code ChatScreen.render} draws the
 * input box background as its first {@code fill(matrices, 2, height-14, width-2, height-2, textBgColor)} (verified by
 * decompile; drawn at the screen base with no GL transform). That {@code ChatScreen.fill} (ordinal 0) is redirected to
 * a frosted glass bar with the hotbar corner (R2) plus a faint grey scrim, so the typed text stays readable. The
 * cursor / suggestion overlay draw later and are left vanilla. Falls back to the vanilla fill when the glass pipeline
 * is unusable.
 */
@Mixin(ChatScreen.class)
public abstract class ChatInputGlassMixin {

    /** Faint grey scrim under the input text, over the glass bar. */
    private static final int INPUT_SCRIM = 0x33101018;

    @Redirect(method = "render",
              at = @At(value = "INVOKE", ordinal = 0,
                       target = "Lnet/minecraft/client/gui/screen/ChatScreen;fill(Lnet/minecraft/client/util/math/MatrixStack;IIIII)V"))
    private void s1mp1e$inputBar(MatrixStack matrices, int x0, int y0, int x1, int y1, int color) {
        if (GlassProgram.ensureReady() && GlassProgram.usable()) {
            HudGlass.glassBoxHotbar(x0, y0, x1, y1, 0.9F);
            DrawableHelper.fill(matrices, x0, y0, x1, y1, INPUT_SCRIM);
        } else {
            DrawableHelper.fill(matrices, x0, y0, x1, y1, color);
        }
    }
}
