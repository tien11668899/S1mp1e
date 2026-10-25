package dev.s1mp1e.client.input;

import org.lwjgl.glfw.GLFW;

/** 26.x {@code net.minecraft.client.input.KeyEvent} look-alike (see {@link Input}); key codes are GLFW's. */
public record KeyEvent(int key, int scancode, int modifiers) {
    public boolean isEscape() { return key == GLFW.GLFW_KEY_ESCAPE; }
}
