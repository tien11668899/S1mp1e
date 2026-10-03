package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.TypingAnim;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.screen.ingame.BookEditScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;
import java.util.List;

/**
 * Book &amp; quill: the page text types like the text boxes (per-glyph entrance, deleted glyphs float away, lines
 * re-flow by gliding) with the blue capsule caret — {@link TypingAnim#extractMultiline}.
 *
 * <p><b>1.13.2.</b> The oldest {@code BookEditScreen}: the text is append-only (no cursor inside the text, no
 * selection) and the caret is not drawn separately — in edit mode {@code render} (javap-read) appends it to the page
 * string ({@code content + §0 + "_"}, grey {@code §7} every other 6 ticks, a bare {@code "_"} for right-to-left)
 * and draws the whole thing with ONE {@code TextRenderer.drawTrimmed(text, x + 36, 34, 116, 0)} — the second
 * {@code drawTrimmed} of the method (the first is the signing page's warning). Here that call is replaced: the caret
 * suffix is cut off, the same wrap gives the lines, each line is mapped back onto its range of the page string, and
 * the animated label draws them at the same origin with its own capsule caret at the end of the text.
 * If the animated path throws, the page falls back to vanilla for good.
 */
@Mixin(BookEditScreen.class)
public abstract class BookEditTypingMixin {

    @Unique private static final int S1_LINE_H = 9;

    @Shadow private boolean signing;
    @Shadow @org.spongepowered.asm.mixin.Final private boolean writeable;

    @Unique private TypingAnim s1mp1e$anim;

    @Redirect(method = "render", at = @At(value = "INVOKE", ordinal = 1,
            target = "Lnet/minecraft/client/font/TextRenderer;drawTrimmed(Ljava/lang/String;IIII)V"))
    private void s1mp1e$pageText(TextRenderer font, String shown, int x, int y, int maxWidth, int color) {
        if (s1mp1e$anim == null) s1mp1e$anim = new TypingAnim();
        if (s1mp1e$anim.broken || this.signing || !this.writeable) {
            font.drawTrimmed(shown, x, y, maxWidth, color);
            return;
        }
        try {
            // cut the vanilla caret off: "§0_", "§7_" or (right-to-left) "_"
            String value = shown;
            int n0 = value.length();
            if (n0 >= 3 && value.charAt(n0 - 3) == '\u00a7' && value.charAt(n0 - 1) == '_') value = value.substring(0, n0 - 3);
            else if (n0 >= 1 && value.charAt(n0 - 1) == '_') value = value.substring(0, n0 - 1);
            int len = value.length();
            // the same wrap drawTrimmed does (it trims trailing newlines first)
            String trimmed = value;
            while (trimmed.endsWith("\n")) trimmed = trimmed.substring(0, trimmed.length() - 1);
            List<String> wrapped = font.wrapLines(trimmed, maxWidth);
            List<int[]> lines = new ArrayList<int[]>(wrapped.size());
            int pos = 0;
            for (String content : wrapped) {
                int b = Math.min(pos, len), e = Math.min(b + content.length(), len);
                lines.add(new int[]{b, e});
                int n = e;
                while (n < len && value.charAt(n) == ' ') n++;        // the wrap drops the space it broke at
                if (n < len && value.charAt(n) == '\n') n++;
                pos = n;
            }
            if (lines.isEmpty()) lines.add(new int[]{0, 0});
            int cursor = len;                                           // 1.13.2: the caret is always at the end
            int cursorLine = lines.size() - 1;
            if (len > 0 && value.charAt(len - 1) == '\n') {            // the caret sits on the new empty line
                lines.add(new int[]{len, len});
                cursorLine = lines.size() - 1;
            }
            s1mp1e$anim.extractMultiline(font, value, lines, x, y, S1_LINE_H, color | 0xFF000000, false,
                    cursor, cursorLine, true);
        } catch (Throwable t) {
            s1mp1e$anim.broken = true;
            System.out.println("[S1mp1e] book typing animation disabled: " + t);
            font.drawTrimmed(shown, x, y, maxWidth, color);
        }
    }
}
