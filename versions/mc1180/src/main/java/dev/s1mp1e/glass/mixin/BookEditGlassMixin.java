package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.gui.screen.ingame.BookEditScreen;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Feature A — the WRITABLE book (edit + sign modes) gets the same glass page + light parchment scrim as
 * {@link BookGlassMixin}. Separate mixin because {@code BookEditScreen} is a distinct {@code Screen} subclass (not a
 * {@code BookScreen}), so its background blit has its own owner and cannot share the other mixin's redirect target.
 *
 * <p>Verified against yarn 1.18.2+build.4: {@code BookEditScreen.render} binds {@code BookScreen.BOOK_TEXTURE} and does
 * one {@code this.drawTexture(MatrixStack, i, 2, 0, 0, 192, 192)} ({@code i = (width-192)/2}) before the editable text;
 * the page art is the rect {@code (20,1)-(166,181)} of that blit. This {@link Redirect}s that blit to a refracting glass plate +
 * a light parchment scrim ({@code 0xD8EFE7D6}, inset 4, radius 4) so dark ink stays readable; the editor text / cursor
 * / page-turn + finalize buttons draw on top, unchanged. Falls back to the vanilla book texture when glass is off.
 */
@Mixin(BookEditScreen.class)
public abstract class BookEditGlassMixin {

    @Redirect(method = "render",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screen/ingame/BookEditScreen;"
                            + "drawTexture(Lnet/minecraft/client/util/math/MatrixStack;IIIIII)V"))
    private void s1mp1e$glassPage(BookEditScreen self, MatrixStack matrices,
                                  int x, int y, int u, int v, int w, int h) {
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) {
            self.drawTexture(matrices, x, y, u, v, w, h);
            return;
        }
        // world + dim already in the framebuffer (renderBackground ran first) -> refract it.
        SceneCapture.grabNow();
        // The visible page inside the 192x192 blit is the rect (20,1)-(166,181) of book.png, NOT its left 146x180 (same
        // correction as BookGlassMixin): the plate used to sit 20 px left of the real page, so the typed text (vanilla
        // draws it at x+36 .. x+150) and the page counter ran off the plate's right edge.
        int px0 = x + 20, py0 = y + 1, px1 = x + 166, py1 = y + 181;
        GlassRenderer.glass(px0, py0, px1, py1, GlassRenderer.PAD_PANEL, 0.19f, 0f, 1f, GlassRenderer.FROST_PANEL);
        if (GlassProgram.roundUsable())
            GlassRenderer.roundRect(px0 + 4, py0 + 4, px1 - 4, py1 - 4, 4f, 0xD8EFE7D6);
    }
}
