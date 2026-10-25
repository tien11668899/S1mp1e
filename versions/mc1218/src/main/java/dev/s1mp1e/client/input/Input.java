package dev.s1mp1e.client.input;

/**
 * 1.21.6-1.21.8 has no input event records: screens and widgets get {@code (double x, double y, int button)},
 * {@code (int key, int scancode, int modifiers)} and {@code (char, int)}. The S1mp1e GUI code is shared with 26.x, where
 * they arrive as {@code MouseButtonEvent} / {@code KeyEvent} / {@code CharacterEvent}; these look-alikes carry the same
 * values so that code ports unchanged, and the 1.21.8 overrides build them with the helpers below.
 */
public final class Input {
    private Input() {}

    public static MouseButtonEvent mouse(double x, double y, int button) {
        return new MouseButtonEvent(x, y, new MouseButtonInfo(button, 0));
    }

    public static KeyEvent key(int key, int scancode, int modifiers) {
        return new KeyEvent(key, scancode, modifiers);
    }

    public static CharacterEvent character(char c, int modifiers) {
        return new CharacterEvent(c, modifiers);
    }
}
