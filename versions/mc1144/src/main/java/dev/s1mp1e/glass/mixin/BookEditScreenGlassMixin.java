package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.GlassScreenPanels;
import net.minecraft.client.gui.screen.ingame.BookEditScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * PORT_SPEC feature (A) — the writable book (edit + sign modes) gets the same glass page + parchment
 * scrim as {@link BookScreenGlassMixin}. Separate mixin because {@code BookEditScreen} is a distinct
 * {@code Screen} subclass (not a {@code BookScreen}), so its {@code blit(IIIIII)} invoke has its own
 * owner and cannot share the other mixin's redirect target.
 *
 * <p>{@code BookEditScreen.render} binds {@code BookScreen.BOOK_TEXTURE} and does one
 * {@code blit(i, 2, 0, 0, 192, 192)} before the editable text; this {@code @Redirect}s it to
 * {@link GlassScreenPanels#book}. The editor text / cursor / buttons draw on top, unchanged.
 */
@Mixin(BookEditScreen.class)
public abstract class BookEditScreenGlassMixin {

    @Redirect(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screen/ingame/BookEditScreen;blit(IIIIII)V"
        )
    )
    private void s1mp1e$glassPage(BookEditScreen self, int x, int y, int u, int v, int w, int h) {
        if (!GlassScreenPanels.book(self, x, y)) {
            self.blit(x, y, u, v, w, h);   // glass unusable: draw the vanilla book background
        }
    }
}
