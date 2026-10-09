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

    @org.spongepowered.asm.mixin.Shadow protected net.minecraft.client.gui.widget.TextFieldWidget chatField;

    /**
     * Arm the input-bar close fade when chat closes: the HUD ({@code ChatCloseGhostMixin}) then paints a ghost of the
     * glass bar plus the typed text lifting off. The text and its screen position are read from the field before it
     * is discarded.
     */
    @org.spongepowered.asm.mixin.injection.Inject(method = "removed", at = @At("HEAD"))
    private void s1mp1e$armCloseFade(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (this.chatField == null) return;
        int x = this.chatField.x + 4;
        int y = this.chatField.y + (this.chatField.getHeight() - 8) / 2;
        dev.s1mp1e.glass.render.ChatCloseFade.begin(this.chatField.getText(), x, y);
    }

    @Redirect(method = "render",
              at = @At(value = "INVOKE", ordinal = 0,
                       target = "Lnet/minecraft/client/gui/screen/ChatScreen;fill(Lnet/minecraft/client/util/math/MatrixStack;IIIII)V"))
    private void s1mp1e$inputBar(MatrixStack matrices, int x0, int y0, int x1, int y1, int color) {
        // fade in with the screen (pressing T): the bar joins the screen-open fade instead of popping
        float fade = dev.s1mp1e.client.gui.ScreenOpenFade.value((ChatScreen) (Object) this);
        if (fade <= 0.004F) return;
        if (GlassProgram.ensureReady() && GlassProgram.usable()) {
            HudGlass.glassBoxHotbar(x0, y0, x1, y1, 0.9F * fade);
            DrawableHelper.fill(matrices, x0, y0, x1, y1, (Math.round(0x33 * fade) & 0xFF) << 24 | (INPUT_SCRIM & 0xFFFFFF));
        } else {
            DrawableHelper.fill(matrices, x0, y0, x1, y1, (Math.round((color >>> 24 & 0xFF) * fade) & 0xFF) << 24 | color & 0xFFFFFF);
        }
    }
}
