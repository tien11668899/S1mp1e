package dev.s1mp1e.client.input;

/** 26.x {@code net.minecraft.client.input.MouseButtonEvent} look-alike (see {@link Input}). */
public record MouseButtonEvent(double x, double y, MouseButtonInfo buttonInfo) {
    public int button() { return buttonInfo.button(); }
    public int modifiers() { return buttonInfo.modifiers(); }
}
