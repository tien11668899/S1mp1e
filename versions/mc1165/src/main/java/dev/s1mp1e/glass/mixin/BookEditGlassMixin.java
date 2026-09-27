package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.GlassScreens;
import net.minecraft.client.gui.screen.ingame.BookEditScreen;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Feature (A) — the WRITE / sign book gets a glass page. The 1.16.5 Fabric port of LiquidGlass26's
 * {@code BookGlassMixin} (BookEdit half). {@code BookEditScreen} does NOT extend {@code BookScreen}, so
 * it needs its own redirect (the {@code drawTexture} INVOKE owner differs).
 *
 * <p>1.16.5 {@code BookEditScreen.render} binds {@code BookScreen.BOOK_TEXTURE} and blits the book once
 * ({@code i = (width-192)/2}, {@code drawTexture(matrices, i, 2, 0, 0, 192, 192)}, javap-verified as the
 * sole {@code BookEditScreen.drawTexture} INVOKE). Redirected to {@link GlassScreens#bookPage} (glass
 * plate + parchment scrim); the editable text / cursor vanilla draws afterwards stays on top. When glass
 * is unusable the vanilla book PNG is drawn.
 */
@Mixin(BookEditScreen.class)
public abstract class BookEditGlassMixin {

    @Redirect(method = "render",
            at = @At(value = "INVOKE", ordinal = 0,
                     target = "Lnet/minecraft/client/gui/screen/ingame/BookEditScreen;"
                            + "drawTexture(Lnet/minecraft/client/util/math/MatrixStack;IIIIII)V"))
    private void s1mp1e$glassPage(BookEditScreen self, MatrixStack matrices,
                                  int x, int y, int u, int v, int w, int h) {
        if (!GlassScreens.bookPage(x, y)) {
            self.drawTexture(matrices, x, y, u, v, w, h);
        }
    }
}
