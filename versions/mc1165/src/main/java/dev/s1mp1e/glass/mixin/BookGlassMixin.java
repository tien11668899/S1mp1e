package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.GlassScreens;
import net.minecraft.client.gui.screen.ingame.BookScreen;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Feature (A) — the READ book (and the lectern, which extends {@code BookScreen}) gets a glass page.
 * The 1.16.5 Fabric port of LiquidGlass26's {@code BookGlassMixin} (BookView half).
 *
 * <p>1.16.5 {@code BookScreen.render} binds {@code BOOK_TEXTURE} and blits it once —
 * {@code drawTexture(matrices, i, 2, 0, 0, 192, 192)} with {@code i = (width-192)/2} (javap-verified,
 * the sole {@code BookScreen.drawTexture} INVOKE). That blit is redirected to
 * {@link GlassScreens#bookPage} (frosted glass plate over the 146x180 page + a light parchment scrim so
 * the dark ink stays readable). The page text vanilla draws afterwards sits on top of the scrim.
 * {@code LecternScreen} inherits {@code BookScreen.render}, so it is covered too. When glass is unusable
 * the vanilla book PNG is drawn.
 */
@Mixin(BookScreen.class)
public abstract class BookGlassMixin {

    @Redirect(method = "render",
            at = @At(value = "INVOKE", ordinal = 0,
                     target = "Lnet/minecraft/client/gui/screen/ingame/BookScreen;"
                            + "drawTexture(Lnet/minecraft/client/util/math/MatrixStack;IIIIII)V"))
    private void s1mp1e$glassPage(BookScreen self, MatrixStack matrices,
                                  int x, int y, int u, int v, int w, int h) {
        if (!GlassScreens.bookPage(x, y)) {
            self.drawTexture(matrices, x, y, u, v, w, h);
        }
    }
}
