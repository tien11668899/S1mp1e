package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.ScreenOpenFade;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.BookEditScreen;
import net.minecraft.client.gui.screen.ingame.BookScreen;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Feature A — the book page (read view + lectern, which extends {@link BookScreen}; and the writable book's edit AND sign
 * modes, {@link BookEditScreen}) becomes a liquid-glass page with a LIGHT warm parchment scrim so dark ink stays readable
 * (the documented dark-ink exception to the grey scrim). 1.21.1 (DrawContext) port of 26.2's {@code BookGlassMixin}
 * ({@code @Mixin({BookViewScreen, BookEditScreen, BookSignScreen})} there).
 *
 * <h3>Seam (verified from 1.21.1 bytecode, yarn 1.21.1+build.3)</h3>
 * {@code BookScreen.renderBackground} and {@code BookEditScreen.renderBackground} both call the in-game dim, then
 * {@code drawTexture(BOOK_TEXTURE, (width-192)/2, 2, 0, 0, 192, 192)} (same descriptor; 1.21.1 folds signing into the
 * edit screen). We {@code @Redirect} that blit to a glass panel over the book page (146x180 at the blit origin) + a
 * light warm parchment scrim (0xD8EFE7D6, inset 4, radius 4). The page text / cursor / sign fields are drawn later in
 * {@code render} and stay on top and readable. Fresh backdrop (R4); fades in with {@link ScreenOpenFade} (150 ms).
 */
@Mixin({BookScreen.class, BookEditScreen.class})
public abstract class BookGlassMixin {

    @Redirect(method = "renderBackground",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/DrawContext;"
                            + "drawTexture(Lnet/minecraft/util/Identifier;IIIIII)V"))
    private void s1mp1e$glassPage(DrawContext ctx, Identifier tex, int x, int y, int u, int v, int w, int h) {
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) {
            ctx.drawTexture(tex, x, y, u, v, w, h);
            return;
        }
        float fade = ScreenOpenFade.value(MinecraftClient.getInstance().currentScreen);
        ctx.draw();
        SceneCapture.grabNow();
        // The book graphic occupies the left 146x180 of the 192x192 blit at (x, y).
        int pw = 146, ph = 180;
        GlassRenderer.panel(x, y, x + pw, y + ph, fade);
        // Light warm parchment scrim so dark ink stays readable (inset 4, radius 4).
        int a = Math.round(0xD8 * fade) & 0xFF;
        GlassRenderer.roundRect(x + 4, y + 4, x + pw - 4, y + ph - 4, 4f, (a << 24) | 0xEFE7D6);
    }
}
