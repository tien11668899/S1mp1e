package dev.s1mp1e.glass.mixin;

import net.minecraft.client.font.FontStorage;
import net.minecraft.client.font.TextRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A string's glyphs are baked before its vertex batch is opened (1.14.4 only).
 *
 * <p>{@code TextRenderer.drawLayer} (javap-read) opens one {@code BufferBuilder} batch and, per character, asks
 * {@code FontStorage.getGlyphRenderer(char)}; when the glyph's atlas page differs from the batch's it flushes the batch
 * and binds the new page. A glyph that is drawn for the first time is baked right there:
 * {@code GlyphAtlasTexture.getGlyphRenderer} <b>binds its own page</b> and uploads the bitmap. If that page is not the
 * one the pending batch belongs to, the flush that follows draws the pending glyphs with the wrong page bound - for
 * that one frame they show whatever sits at their UVs on the other page (a block of stripes).
 *
 * <p>With the vanilla bitmap font everything lives on one page, so the game never shows it. With the PingFang TTF a
 * page holds about a hundred 24 px CJK glyphs, new glyphs are placed first-fit over many pages, and a mixed string
 * crosses pages all the time; vanilla's shadow pass used to take the hit (the bake happened under the dark copy), but
 * this client draws no text shadows, so the main pass would show it - seen on the first frame of the sidebar fade-in.
 *
 * <p>Fix: at the head of the private {@code draw(String, float, float, int, boolean)} - the one point every string
 * passes (see {@link TextShadowMixin}) - every character is looked up once, so all baking (and its texture binds,
 * which go through {@code GlStateManager} and keep its cache right) happens before the batch exists. The lookup is the
 * same cached map read {@code drawLayer} does itself. Formatting codes are skipped the way {@code drawLayer} skips
 * them; obfuscated text (random stand-in glyphs) is left to vanilla.
 */
@Mixin(TextRenderer.class)
public abstract class GlyphPrebakeMixin {

    @Shadow @Final private FontStorage fontStorage;

    @Inject(method = "draw(Ljava/lang/String;FFIZ)I", at = @At("HEAD"))
    private void s1mp1e$prebake(String text, float x, float y, int color, boolean shadow,
                                CallbackInfoReturnable<Integer> cir) {
        if (text == null) return;
        try {
            FontStorage storage = this.fontStorage;
            for (int i = 0, n = text.length(); i < n; i++) {
                char c = text.charAt(i);
                if (c == 167 && i + 1 < n) {      // section sign + code: not drawn
                    i++;
                    continue;
                }
                storage.getGlyphRenderer(c);
            }
        } catch (Throwable ignored) {
            // the draw itself will bake what is missing, exactly like vanilla
        }
    }
}
