package dev.s1mp1e.o.client;

import net.minecraft.client.gui.screen.Screen;

/**
 * A module that has its own dedicated layout editor (opened from a "編輯排版" chip in the config menu),
 * distinct from the general HUD editor. Keystrokes uses this so each key can be positioned individually
 * in its own editor, while the module still drags as ONE whole block in the general HUD editor.
 *
 * <p>1.8.9 has no {@code Screen}; the editor is a {@link Screen} shown via
 * {@code Minecraft#displayGuiScreen}.
 */
public interface LayoutEditable {
    /** A fresh editor screen for this module's internal layout. */
    Screen openLayoutEditor();
}
