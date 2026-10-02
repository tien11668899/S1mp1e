package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.module.HudGlass;
import dev.s1mp1e.glass.render.ChatCloseFade;
import dev.s1mp1e.glass.render.GlassProgram;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The open chat input field becomes a liquid-glass bar (G1).
 *
 * <p>1.21.1 path: {@code ChatScreen.render} draws its input box as a single
 * {@code context.fill(2, height-14, width-2, height-2, backgroundColor)}. That fill is redirected to a
 * frosted glass bar (refracting panel + a faint grey scrim so the typed text and the command-suggestion
 * box stay readable) at the same rectangle. The screen has no pose transform here, so the bake is a
 * pass-through. Falls back to the vanilla fill when the glass pipeline is not usable.
 */
@Mixin(ChatScreen.class)
public abstract class ChatInputGlassMixin {

    /** Grey readability scrim over the glass, under the typed text (26.2 0x33101018). */
    private static final int INPUT_SCRIM = 0x33101018;

    @Shadow protected TextFieldWidget chatField;

    /**
     * Arm the input-bar close fade when chat closes: the HUD ({@code ChatCloseGhostMixin}) then paints a ghost of the glass
     * bar plus the typed text lifting off. The text and its screen position are read from the field before it is discarded.
     */
    @Inject(method = "removed", at = @At("HEAD"))
    private void s1mp1e$armCloseFade(CallbackInfo ci) {
        if (this.chatField == null) return;
        int x = this.chatField.getX() + 4;
        int y = this.chatField.getY() + (this.chatField.getHeight() - 8) / 2;
        ChatCloseFade.begin(this.chatField.getText(), x, y);
    }

    @Redirect(
        method = "render",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/DrawContext;fill(IIIII)V")
    )
    private void s1mp1e$inputBar(DrawContext ctx, int x0, int y0, int x1, int y1, int color) {
        if (GlassProgram.ensureReady() && GlassProgram.usable()) {
            HudGlass.glassBoxCtx(ctx, x0, y0, x1, y1, 0.9F);
            HudGlass.roundFillCtx(ctx, x0, y0, x1, y1, 0F, INPUT_SCRIM);
        } else {
            ctx.fill(x0, y0, x1, y1, color);
        }
    }
}
