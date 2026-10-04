package dev.s1mp1e.glass.hook;

import dev.s1mp1e.glass.render.TypingAnim;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiTextField;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Silky typing for every {@link GuiTextField} (chat input, anvil rename, world / server names, search fields) — group 6,
 * the 1.12.2 counterpart of mc1144's {@code EditBoxTypingMixin}. The coremod splices
 * {@code if (EditBoxHook.draw(this)) return;} onto the head of {@code GuiTextField.drawTextBox}; this then draws the
 * box exactly as vanilla lays it out (same frame rects, same text origin / inner width) but with the text, caret and
 * selection from {@link TypingAnim}: per-glyph entrance (blur&rarr;sharp + rise + fade), exit on delete, glide after a
 * mid-string edit, eased horizontal scroll, the system-blue capsule caret with a smooth blink, and a selection with
 * gliding edges. Anything unexpected returns false (vanilla draws) and a box whose animation threw stays vanilla for
 * good ({@link TypingAnim#broken}). No drop shadow (global rule).
 */
public final class EditBoxHook {

    private EditBoxHook() {}

    private static final Map<GuiTextField, TypingAnim> ANIMS = new WeakHashMap<GuiTextField, TypingAnim>();

    private static Field fFont, fScroll, fEnabled, fEnabledColor, fDisabledColor;
    private static boolean resolved, ok;

    private static void resolve() {
        if (resolved) return;
        resolved = true;
        fFont = field("field_146211_a", "fontRenderer");
        fScroll = field("field_146225_q", "lineScrollOffset");
        fEnabled = field("field_146226_p", "isEnabled");
        fEnabledColor = field("field_146222_t", "enabledColor");
        fDisabledColor = field("field_146221_u", "disabledColor");
        ok = fFont != null && fScroll != null && fEnabled != null && fEnabledColor != null && fDisabledColor != null;
    }

    private static Field field(String srg, String mcp) {
        for (String n : new String[]{srg, mcp}) {
            try { Field f = GuiTextField.class.getDeclaredField(n); f.setAccessible(true); return f; }
            catch (NoSuchFieldException ignored) {}
        }
        return null;
    }

    public static boolean draw(GuiTextField box) {
        try {
            resolve();
            if (!ok || box == null) return false;
            TypingAnim anim = ANIMS.get(box);
            if (anim == null) { anim = new TypingAnim(); ANIMS.put(box, anim); }
            if (anim.broken) return false;
            if (!box.getVisible()) return true;               // vanilla draws nothing either

            boolean bg = box.getEnableBackgroundDrawing();
            if (bg) {
                Gui.drawRect(box.x - 1, box.y - 1, box.x + box.width + 1, box.y + box.height + 1, -6250336);
                Gui.drawRect(box.x, box.y, box.x + box.width, box.y + box.height, -16777216);
            }
            FontRenderer font = (FontRenderer) fFont.get(box);
            int scroll = fScroll.getInt(box);
            boolean enabled = fEnabled.getBoolean(box);
            int color = enabled ? fEnabledColor.getInt(box) : fDisabledColor.getInt(box);
            int textX = bg ? box.x + 4 : box.x;
            int textY = bg ? box.y + (box.height - 8) / 2 : box.y;
            String text = box.getText();
            int cursor = box.getCursorPosition();
            int sel = box.getSelectionEnd();
            try {
                anim.extractText(font, text, text, scroll, cursor, textX, textY, box.getWidth(), color, false);
                if (sel != cursor) anim.drawHighlight(sel, textY - 1, textY + 1 + font.FONT_HEIGHT);
                if (box.isFocused()) anim.drawCaret();
            } catch (Throwable t) {
                anim.broken = true;
                System.out.println("[S1mp1e] typing animation disabled for a GuiTextField: " + t);
                return false;                                  // this frame (and after): vanilla
            }
            dev.s1mp1e.client.gui.GlassWidgets.resetColorCache();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
