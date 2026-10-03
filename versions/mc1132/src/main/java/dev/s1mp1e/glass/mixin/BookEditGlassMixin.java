package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.GlassScreens;
import net.minecraft.client.gui.screen.ingame.BookEditScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Feature (A) — the WRITE / sign book gets a glass page. The 1.13.2 Fabric port of LiquidGlass26's
 * {@code BookGlassMixin} (BookEdit half). {@code BookEditScreen} does NOT extend {@code BookScreen}, so
 * it needs its own redirect (the {@code drawTexture} INVOKE owner differs).
 *
 * <p>1.13.2 {@code BookEditScreen.render} binds {@code BookScreen.BOOK_TEXTURE} and blits the book once
 * ({@code i = (width-192)/2}, {@code drawTexture(i, 2, 0, 0, 192, 192)}, javap-verified as the
 * sole {@code BookEditScreen.drawTexture} INVOKE). Redirected to {@link GlassScreens#bookPage} (glass
 * plate + parchment scrim); the editable text / cursor vanilla draws afterwards stays on top. When glass
 * is unusable the vanilla book PNG is drawn.
 */
@Mixin(BookEditScreen.class)
public abstract class BookEditGlassMixin {

    @Redirect(method = "render",
            at = @At(value = "INVOKE", ordinal = 0,
                     target = "Lnet/minecraft/client/gui/screen/ingame/BookEditScreen;drawTexture(IIIIII)V"))
    private void s1mp1e$glassPage(BookEditScreen self, int x, int y, int u, int v, int w, int h) {
        if (!GlassScreens.bookPage(x, y)) {
            self.drawTexture(x, y, u, v, w, h);
        }
    }

    /**
     * 1.13.2: the old book screen draws no background at all (the world stays undimmed behind the page, the HUD
     * visible) — every newer line dims here ({@code renderBackground} joined the book screens in 1.14). Same dim, so
     * the glass page reads the same on every line.
     */
    @org.spongepowered.asm.mixin.injection.Inject(method = "render", at = @At("HEAD"))
    private void s1mp1e$dim(int mouseX, int mouseY, float delta,
                            org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        ((BookEditScreen) (Object) this).renderBackground();
    }
}
