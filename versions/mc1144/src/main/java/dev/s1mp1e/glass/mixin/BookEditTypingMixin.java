package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.TypingAnim;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawableHelper;
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
 * <p><b>1.14.4.</b> This is the old {@code BookEditScreen} (no {@code SelectionManager}, no page-content cache, no
 * {@code drawCursor} / {@code drawSelection}): in edit mode {@code render} (javap-read) draws the page indicator, then
 * the whole page with ONE {@code TextRenderer.drawTrimmed(content, x + 36, 32, 114, 0)} (which word-wraps through
 * {@code wrapStringToWidthAsList}), then {@code drawHighlight(content)} and, every other 6 ticks, the caret — a
 * {@code DrawableHelper.fill} bar inside the text or a {@code "_"} at its end. Here:
 * <ul>
 *   <li>that {@code drawTrimmed} (the second in the method; the first is the signing page's text) is replaced: the
 *       same wrap gives the lines, each line is mapped back onto its range of the page string, and the animated
 *       label draws them at the same origin;</li>
 *   <li>both vanilla caret forms are dropped while the animation runs (its own capsule replaces the on/off blink);
 *       the selection highlight stays vanilla.</li>
 * </ul>
 * If the animated path throws, the page falls back to vanilla for good.
 */
@Mixin(BookEditScreen.class)
public abstract class BookEditTypingMixin {

    @Unique private static final int S1_LINE_H = 9;

    @Shadow private boolean signing;
    @Shadow private int cursorIndex;

    @Unique private TypingAnim s1mp1e$anim;

    @Unique
    private boolean s1mp1e$animated() {
        return s1mp1e$anim != null && !s1mp1e$anim.broken && !this.signing;
    }

    @Redirect(method = "render", at = @At(value = "INVOKE", ordinal = 1,
            target = "Lnet/minecraft/client/font/TextRenderer;drawTrimmed(Ljava/lang/String;IIII)V"))
    private void s1mp1e$pageText(TextRenderer font, String value, int x, int y, int maxWidth, int color) {
        if (s1mp1e$anim == null) s1mp1e$anim = new TypingAnim();
        if (s1mp1e$anim.broken || this.signing) {
            font.drawTrimmed(value, x, y, maxWidth, color);
            return;
        }
        try {
            int len = value.length();
            // the same wrap drawTrimmed does (it trims trailing newlines first)
            String trimmed = value;
            while (trimmed.endsWith("\n")) trimmed = trimmed.substring(0, trimmed.length() - 1);
            List<String> wrapped = font.wrapStringToWidthAsList(trimmed, maxWidth);
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
            int cursor = Math.max(0, Math.min(this.cursorIndex, len));
            int cursorLine = 0;
            for (int i = 0; i < lines.size(); i++) if (lines.get(i)[0] <= cursor) cursorLine = i;
            if (len > 0 && value.charAt(len - 1) == '\n' && cursor == len) {     // the caret sits on the new empty line
                lines.add(new int[]{len, len});
                cursorLine = lines.size() - 1;
            }
            s1mp1e$anim.extractMultiline(font, value, lines, x, y, S1_LINE_H, color | 0xFF000000, false,
                    cursor, cursorLine, true);
        } catch (Throwable t) {
            s1mp1e$anim.broken = true;
            System.out.println("[S1mp1e] book typing animation disabled: " + t);
            font.drawTrimmed(value, x, y, maxWidth, color);
        }
    }

    @Redirect(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/DrawableHelper;fill(IIIII)V"))
    private void s1mp1e$caretBar(int x0, int y0, int x1, int y1, int color) {
        if (!s1mp1e$animated()) DrawableHelper.fill(x0, y0, x1, y1, color);
    }

    /** The fifth {@code draw} of the method is the end-of-text {@code "_"} caret (0-2: signing page, 3: page indicator). */
    @Redirect(method = "render", at = @At(value = "INVOKE", ordinal = 4,
            target = "Lnet/minecraft/client/font/TextRenderer;draw(Ljava/lang/String;FFI)I"))
    private int s1mp1e$caretUnderscore(TextRenderer font, String text, float x, float y, int color) {
        return s1mp1e$animated() ? (int) x : font.draw(text, x, y, color);
    }
}
