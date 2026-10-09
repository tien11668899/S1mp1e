package dev.s1mp1e.client;

import net.minecraft.client.gui.screens.Screen;

/**
 * A module that has its own dedicated layout editor (opened from a "編輯排版" chip in the config menu),
 * distinct from the general HUD editor. Keystrokes uses this so each key can be positioned individually
 * in its own editor, while the module still drags as ONE whole block in the general HUD editor.
 */
public interface LayoutEditable {
    /** A fresh editor screen for this module's internal layout. */
    Screen openLayoutEditor();
}
