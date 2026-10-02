package dev.s1mp1e.glass.mixin;

import java.util.ArrayList;
import java.util.List;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.s1mp1e.glass.render.TypingAnim;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.BookEditScreen;
import net.minecraft.client.util.SelectionManager;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The book &amp; quill page types like the text box: glyphs rise / sharpen / fade in, deleted ones float away, word-wrap
 * moves glide in 2-D, and the caret is the gliding Apple capsule — {@link TypingAnim#extractMultiline}. 1.17.1 port of
 * 26.2's {@code MultiLineTypingMixin}.
 *
 * <p>26.2 edits a book through a {@code MultiLineEditBox}; 1.17.1's {@code BookEditScreen} lays the page out itself
 * (a private {@code PageContent} of pre-wrapped lines) and draws it in {@code render} — javap-read on the 1.17.1
 * class: the page indicator, then {@code TextRenderer.draw(MatrixStack, Text, x, y, color)} once per line, then
 * {@code drawSelection(Rect2i[])}, then {@code drawCursor} (a 1 px {@code DrawableHelper.fill} bar inside the text, or
 * a {@code "_"} at its end). The inner layout classes are not nameable from here, so nothing of them is touched: the
 * per-line draw is wrapped (the 4th {@code draw(MatrixStack, Text, …)} of {@code render}; the first three are the
 * signing page's two captions and the page indicator), each call giving that line's text; the lines' offsets into the
 * page string are rebuilt from the string itself (vanilla lines are contiguous — each starts where the previous one's
 * trailing spaces / single newline end). The animated page is drawn right before {@code drawSelection}; both vanilla
 * caret forms are suppressed while the animation is healthy. Any failure falls back to vanilla for good.
 */
@Mixin(BookEditScreen.class)
public abstract class BookEditTypingMixin {

    /** Vanilla's page text origin: {@code absolutePositionToScreenPosition} = ((width − 192) / 2 + 36, 32). */
    @Unique private static final int S1_LEFT = 36, S1_TOP = 32, S1_BOOK_W = 192, S1_LINE_H = 9;

    @Shadow private boolean signing;
    @Shadow @Final private SelectionManager currentPageSelectionManager;
    @Shadow private String getCurrentPageContent() { throw new AssertionError(); }

    @Unique private TypingAnim s1mp1e$anim;
    @Unique private final List<String> s1mp1e$lineTexts = new ArrayList<>();
    /** Colour vanilla (or another mod's recolour) passed for the page text this frame. */
    @Unique private int s1mp1e$lineColor = 0xFF000000;

    @Unique
    private boolean s1mp1e$animated() {
        return s1mp1e$anim != null && !s1mp1e$anim.broken && !this.signing;
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void s1mp1e$begin(MatrixStack matrices, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        s1mp1e$lineTexts.clear();
        if (s1mp1e$anim == null) s1mp1e$anim = new TypingAnim();
    }

    /** The per-line page text: recorded and suppressed while animated (the animated pass below draws the page). */
    @WrapOperation(method = "render", at = @At(value = "INVOKE", ordinal = 3,
            target = "Lnet/minecraft/client/font/TextRenderer;draw(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/text/Text;FFI)I"))
    private int s1mp1e$lineText(TextRenderer font, MatrixStack matrices, Text text, float x, float y, int color,
                                Operation<Integer> op) {
        if (!s1mp1e$animated()) return op.call(font, matrices, text, x, y, color);
        s1mp1e$lineTexts.add(text.getString());
        s1mp1e$lineColor = color;
        return (int) x;
    }

    @Inject(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screen/ingame/BookEditScreen;drawSelection([Lnet/minecraft/client/util/math/Rect2i;)V"))
    private void s1mp1e$drawAnimated(MatrixStack matrices, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!s1mp1e$animated()) return;
        try {
            String value = getCurrentPageContent();
            int len = value.length();
            List<int[]> lines = new ArrayList<>(s1mp1e$lineTexts.size());
            int pos = 0;
            for (String content : s1mp1e$lineTexts) {
                int b = Math.min(pos, len), e = Math.min(b + content.length(), len);
                lines.add(new int[]{b, e});
                // next line starts after this one's trailing spaces and at most one newline
                int n = e;
                while (n < len && value.charAt(n) == ' ') n++;
                if (n < len && value.charAt(n) == '\n') n++;
                pos = n;
            }
            int cursor = Math.max(0, Math.min(this.currentPageSelectionManager.getSelectionStart(), len));
            int cursorLine = 0;
            for (int i = 0; i < lines.size(); i++) if (lines.get(i)[0] <= cursor) cursorLine = i;
            TextRenderer font = net.minecraft.client.MinecraftClient.getInstance().textRenderer;
            int left = (((Screen) (Object) this).width - S1_BOOK_W) / 2 + S1_LEFT;
            s1mp1e$anim.extractMultiline(matrices, font, value, lines, left, S1_TOP, S1_LINE_H, s1mp1e$lineColor, false,
                    cursor, cursorLine, true);
        } catch (Throwable t) {
            s1mp1e$anim.broken = true;
            System.out.println("[S1mp1e] book typing animation disabled: " + t);
        }
    }

    /** Vanilla's within-text caret bar: the animated capsule replaces it. */
    @Redirect(method = "drawCursor", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/DrawableHelper;fill(Lnet/minecraft/client/util/math/MatrixStack;IIIII)V"))
    private void s1mp1e$caretBar(MatrixStack matrices, int x0, int y0, int x1, int y1, int color) {
        if (!s1mp1e$animated()) DrawableHelper.fill(matrices, x0, y0, x1, y1, color);
    }

    /** Vanilla's end-of-text {@code _} caret: replaced too. */
    @Redirect(method = "drawCursor", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/font/TextRenderer;draw(Lnet/minecraft/client/util/math/MatrixStack;Ljava/lang/String;FFI)I"))
    private int s1mp1e$caretUnderscore(TextRenderer font, MatrixStack matrices, String text, float x, float y, int color) {
        return s1mp1e$animated() ? (int) x : font.draw(matrices, text, x, y, color);
    }
}
