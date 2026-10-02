package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassSurface;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.BookEditScreen;
import net.minecraft.client.gui.screen.ingame.BookScreen;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Book view / edit (+ lectern, which reuses {@link BookScreen}) → the page becomes a refracting liquid-glass plate with a
 * LIGHT warm parchment scrim so the dark ink stays readable (26.2's {@code BookGlassMixin} ported to 1.20.1). The
 * documented dark-ink exception to the usual grey scrim: the scrim here is {@code 0xD8EFE7D6} (warm cream), inset 4 px,
 * radius 4.
 *
 * <p>Both screens blit {@code BOOK_TEXTURE} at {@code (i, 2)} as a 192x192 region (the page graphic is 146x180 in it); we
 * redirect that single blit to the plate + parchment scrim, leaving the page text/arrows/buttons on top.
 */
@Mixin({BookScreen.class, BookEditScreen.class})
public abstract class BookGlassMixin {

    @Redirect(method = "render",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/DrawContext;"
                            + "drawTexture(Lnet/minecraft/util/Identifier;IIIIII)V"))
    private void s1mp1e$glassPage(DrawContext context, Identifier texture, int x, int y, int u, int v, int w, int h) {
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) {
            context.drawTexture(texture, x, y, u, v, w, h);
            return;
        }
        SceneCapture.grabNow();
        // The visible page inside the 192x192 blit. Measured on book.png (1.20.1 and 1.21.1 alike): the page art is the
        // rect (20,1)-(166,181), NOT the left 146x180 — the plate used to sit 20 px left of the real page, so the page
        // text (vanilla draws it at x+36 .. x+150) and the page counter ran off the plate's right edge.
        int px0 = x + 20, py0 = y + 1, px1 = x + 166, py1 = y + 181;
        GlassSurface.plateOrPaint(context, px0, py0, px1, py1, 1.0f, 0x99101014);
        // light warm parchment scrim (dark-ink exception), inset 4, radius 4
        GlassSurface.scrim(context, px0 + 4, py0 + 4, px1 - 4, py1 - 4, 4f, 0xD8EFE7D6);
    }
}
