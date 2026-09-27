package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.GlassScreenPanels;
import net.minecraft.client.gui.screen.ingame.BookScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * PORT_SPEC feature (A) — the read-only book / lectern page becomes a glass plate with a light warm
 * parchment scrim so the dark ink stays readable. 1.15.2 Fabric counterpart of 26.2's
 * {@code BookGlassMixin} (identical to the 1.14.4 sibling).
 *
 * <p>{@code BookScreen.render} binds {@code BOOK_TEXTURE} and does exactly one {@code blit(i, 2, 0,
 * 0, 192, 192)} (the open-book background; javap-verified: a single {@code blit(IIIIII)} invoke in
 * {@code render}) before drawing the page text. This {@code @Redirect}s that blit to
 * {@link GlassScreenPanels#book}, which draws a refracting glass plate over the {@code 146x180} page
 * + the parchment scrim and drops the wooden book. The page text then draws on top, unchanged. When
 * glass is unusable the vanilla book is drawn.
 *
 * <p>{@code LecternScreen extends BookScreen} and does not override {@code render} (javap-verified),
 * so the lectern is covered by this same mixin. Page-turn arrows are separate {@code PageTurnWidget}s
 * and are untouched.
 */
@Mixin(BookScreen.class)
public abstract class BookScreenGlassMixin {

    @Redirect(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screen/ingame/BookScreen;blit(IIIIII)V"
        )
    )
    private void s1mp1e$glassPage(BookScreen self, int x, int y, int u, int v, int w, int h) {
        if (!GlassScreenPanels.book(self, x, y)) {
            self.blit(x, y, u, v, w, h);   // glass unusable: draw the vanilla book background
        }
    }
}
