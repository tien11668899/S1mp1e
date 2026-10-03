package dev.s1mp1e.glass.render;

/**
 * 1.13.2 stand-in for the 1.20 {@code DrawContext.draw()} call that the newer lines put before every raw-GL glass
 * draw (and that the code shared with those lines still calls).
 *
 * <p>On 1.13.2 ALL GUI drawing is immediate: fills and blits go straight through the {@code Tessellator}, text is
 * drawn glyph batch by glyph batch on the spot and a GUI item model is tessellated and drawn at once (there are no
 * buffered {@code VertexConsumerProvider}s before 1.15). So nothing is ever queued when a glass piece is drawn and
 * this is a no-op, kept so the shared code reads the same on every line.
 */
public final class GuiFlush {

    private GuiFlush() {}

    /** Draw everything the GUI has buffered so far: nothing on 1.13.2. */
    public static void flush() {
    }
}
