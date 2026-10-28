package dev.s1mp1e.glass.mixin;

import net.minecraft.client.font.TrueTypeFont;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Fixes TTF glyphs (our PingFang) being drawn to the right of their place by their own left side bearing: the reason
 * the full-width "（" (U+FF08) showed as a blank gap on 1.19.2 and 1.20.1.
 *
 * <p>1.19.2's (as 1.20.1's) {@code TrueTypeFont.getGlyph} puts a glyph's bitmap at {@code (lsb * scale + x0 + shiftX) / oversample}
 * from the pen. But {@code x0}, the left edge of {@code stbtt_GetGlyphBitmapBoxSubpixel}'s box, is already
 * {@code floor(xMin * scale + shiftX)}, and a TrueType glyph's {@code xMin} is its left side bearing, so the bearing (and
 * the X shift) is counted twice. Latin letters and CJK ideographs have small bearings and only moved 0.1-1 GUI px. A
 * full-width opening bracket sits at the right of its em box, though: "（" has a bearing of 669/1000 em, so it was drawn
 * 5.7 px too far right, inside the next character's left stroke ("疾跑（切換）" read "疾跑　切換）"). 「 『 【 《 〈 were
 * hit the same way. 1.21's FreeType path places the bitmap by its own left edge only.
 *
 * <p>The bearing argument becomes {@code -shiftX}, so the bitmap lands at {@code x0 / oversample}: its box's left edge,
 * which carries the bearing and the X shift once. Advances are unchanged, so text widths and layout stay the same. The
 * Y shift is counted twice too, but that moves every glyph alike and puts the baseline where 1.21 puts it (y + 8), so it
 * stays.
 */
@Mixin(TrueTypeFont.class)
public abstract class FontBearingFixMixin {
    @Shadow @Final float shiftX;

    @ModifyArg(method = "getGlyph",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/font/TrueTypeFont$TtfGlyph;<init>(Lnet/minecraft/client/font/TrueTypeFont;IIIIFFI)V"),
            index = 6)
    private float s1mp1e$bearingFromBitmapBox(float leftSideBearing) {
        return -this.shiftX;
    }
}
