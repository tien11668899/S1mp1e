package dev.s1mp1e.o.glass.hook;

import dev.s1mp1e.o.glass.render.TypingAnim;
import net.minecraft.client.render.TextRenderer;
import net.minecraft.client.gui.GuiElement;
import net.minecraft.client.gui.widget.TextFieldWidget;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Silky typing for every {@link TextFieldWidget} (chat input, anvil rename, world / server names, search fields) — group 6,
 * the 1.12.2 counterpart of mc1144's {@code EditBoxTypingMixin}. The coremod splices
 * {@code if (EditBoxHook.draw(this)) return;} onto the head of {@code TextFieldWidget.render}; this then draws the
 * box exactly as vanilla lays it out (same frame rects, same text origin / inner width) but with the text, caret and
 * selection from {@link TypingAnim}: per-glyph entrance (blur&rarr;sharp + rise + fade), exit on delete, glide after a
 * mid-string edit, eased horizontal scroll, the system-blue capsule caret with a smooth blink, and a selection with
 * gliding edges. Anything unexpected returns false (vanilla draws) and a box whose animation threw stays vanilla for
 * good ({@link TypingAnim#broken}). No drop shadow (global rule).
 */
public final class EditBoxHook {

    private EditBoxHook() {}

    private static final Map<TextFieldWidget, TypingAnim> ANIMS = new WeakHashMap<TextFieldWidget, TypingAnim>();

    private static Field fFont, fScroll, fEnabled, fEnabledColor, fDisabledColor;
    private static boolean resolved, ok;

    private static void resolve() {
        if (resolved) return;
        resolved = true;
        fFont = field(dev.s1mp1e.o.util.Names.of("textRenderer", "f_08457791"), "fontRendererInstance");
        fScroll = field(dev.s1mp1e.o.util.Names.of("firstCharacterIndex", "f_26530929"), "lineScrollOffset");
        fEnabled = field(dev.s1mp1e.o.util.Names.of("editable", "f_45058509"), "isEnabled");
        fEnabledColor = field(dev.s1mp1e.o.util.Names.of("editableColor", "f_60857706"), "enabledColor");
        fDisabledColor = field(dev.s1mp1e.o.util.Names.of("uneditableColor", "f_29487991"), "disabledColor");
        ok = fFont != null && fScroll != null && fEnabled != null && fEnabledColor != null && fDisabledColor != null;
    }

    private static Field field(String srg, String mcp) {
        for (String n : new String[]{srg, mcp}) {
            try { Field f = TextFieldWidget.class.getDeclaredField(n); f.setAccessible(true); return f; }
            catch (NoSuchFieldException ignored) {}
        }
        return null;
    }

    public static boolean draw(TextFieldWidget box) {
        try {
            resolve();
            if (!ok || box == null) return false;
            TypingAnim anim = ANIMS.get(box);
            if (anim == null) { anim = new TypingAnim(); ANIMS.put(box, anim); }
            if (anim.broken) return false;
            if (!box.isVisible()) return true;               // vanilla draws nothing either

            boolean bg = box.hasBorder();
            if (bg) {
                GuiElement.fill(box.x - 1, box.y - 1, box.x + box.width + 1, box.y + box.height + 1, -6250336);
                GuiElement.fill(box.x, box.y, box.x + box.width, box.y + box.height, -16777216);
            }
            TextRenderer font = (TextRenderer) fFont.get(box);
            int scroll = fScroll.getInt(box);
            boolean enabled = fEnabled.getBoolean(box);
            int color = enabled ? fEnabledColor.getInt(box) : fDisabledColor.getInt(box);
            int textX = bg ? box.x + 4 : box.x;
            int textY = bg ? box.y + (box.height - 8) / 2 : box.y;
            String text = box.getText();
            int cursor = box.getCursor();
            int sel = box.getSelectionEnd();
            try {
                anim.extractText(font, text, text, scroll, cursor, textX, textY, box.getInnerWidth(), color, false);
                if (sel != cursor) anim.drawHighlight(sel, textY - 1, textY + 1 + font.fontHeight);
                if (box.isFocused()) anim.drawCaret();
            } catch (Throwable t) {
                anim.broken = true;
                System.out.println("[S1mp1e] typing animation disabled for a TextFieldWidget: " + t);
                return false;                                  // this frame (and after): vanilla
            }
            dev.s1mp1e.o.client.gui.GlassWidgets.resetColorCache();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
