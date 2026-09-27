package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.gui.screen.ingame.BookScreen;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Feature A — the written-book VIEW page becomes a glass plate with a LIGHT warm parchment scrim, the documented
 * dark-ink exception to the grey scrim (so black ink stays readable).
 *
 * <p>Verified against yarn 1.19.2+build.28: {@code BookScreen.render} draws {@code renderBackground} then blits the
 * {@code BOOK_TEXTURE} with {@code this.drawTexture(MatrixStack, i, 2, 0, 0, 192, 192)} where {@code i = (width-192)/2};
 * the book art occupies roughly the top-left {@code 146 x 180} of that sheet. This mixin {@link Redirect}s that single
 * blit to a refracting glass plate over the page + a light parchment scrim ({@code 0xD8EFE7D6}, inset 4, radius 4); the
 * page text and the page-turn buttons draw afterwards and stay on top. Falls back to the vanilla book texture when the
 * glass pipeline is unavailable.
 */
@Mixin(BookScreen.class)
public abstract class BookGlassMixin {

    @Redirect(method = "render",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screen/ingame/BookScreen;"
                            + "drawTexture(Lnet/minecraft/client/util/math/MatrixStack;IIIIII)V"))
    private void s1mp1e$glassPage(BookScreen self, MatrixStack matrices,
                                  int x, int y, int u, int v, int w, int h) {
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) {
            self.drawTexture(matrices, x, y, u, v, w, h);
            return;
        }
        // world + dim already in the framebuffer (renderBackground ran first) -> refract it.
        SceneCapture.grabNow();
        int pw = 146, ph = 180;
        GlassRenderer.glass(x, y, x + pw, y + ph, GlassRenderer.PAD_PANEL, 0.19f, 0f, 1f, GlassRenderer.FROST_PANEL);
        if (GlassProgram.roundUsable())
            GlassRenderer.roundRect(x + 4, y + 4, x + pw - 4, y + ph - 4, 4f, 0xD8EFE7D6);
    }
}
