package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.module.HudGlass;
import dev.s1mp1e.glass.render.GlassProgram;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.toast.AdvancementToast;
import net.minecraft.client.toast.RecipeToast;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.client.toast.TutorialToast;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Advancement / recipe / system / tutorial toasts become liquid-glass cards (G4).
 *
 * <p>1.20.1 path (verified by decompile): each concrete toast draws its background frame as the first
 * {@code context.drawTexture(TEXTURE, x, y, u, v, width, height)} of its {@code draw(DrawContext,
 * ToastManager, long)} method (the 7-arg atlas blit). That blit (ordinal 0) is redirected to a frosted
 * glass card plus a faint grey scrim for text readability. Because the redirect sits at the blit call
 * site — after the {@code ToastManager} has applied the toast's slide-in pose translation — the card is
 * baked through the current ctx matrix and so inherits the slide, moving with the toast. The toast's
 * own icon and text draw on top unchanged. Falls back to the vanilla sprite when the glass pipeline is
 * not usable.
 *
 * <p><b>SystemToast note.</b> A multi-line SystemToast draws its background as a nine-slice through the
 * private {@code drawPart} helper (not a direct {@code drawTexture} in {@code draw}); only the common
 * single-line 160-px form has the ordinal-0 blit here, so only that form is glassed — the rarer
 * multi-line form keeps its vanilla frame. Advancement / recipe / tutorial toasts are always a single
 * background blit and are always glassed.
 */
@Mixin({ AdvancementToast.class, RecipeToast.class, SystemToast.class, TutorialToast.class })
public abstract class ToastGlassMixin {

    /** Grey readability scrim over the card, under the icon/text (26.2 0x30101018). */
    private static final int CARD_SCRIM = 0x30101018;

    @Redirect(
        method = "draw",
        at = @At(
            value = "INVOKE",
            ordinal = 0,
            target = "Lnet/minecraft/client/gui/DrawContext;drawTexture(Lnet/minecraft/util/Identifier;IIIIII)V"
        )
    )
    private void s1mp1e$toastCard(DrawContext ctx, Identifier tex, int x, int y, int u, int v, int w, int h) {
        if (GlassProgram.ensureReady() && GlassProgram.usable()) {
            HudGlass.glassBoxCtx(ctx, x, y, x + w, y + h, 0.92F);
            HudGlass.roundFillCtx(ctx, x, y, x + w, y + h, 6F, CARD_SCRIM);
        } else {
            ctx.drawTexture(tex, x, y, u, v, w, h);
        }
    }
}
