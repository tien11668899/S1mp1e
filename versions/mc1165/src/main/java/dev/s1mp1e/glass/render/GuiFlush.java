package dev.s1mp1e.glass.render;

import net.minecraft.client.MinecraftClient;

/**
 * 1.16.5 stand-in for the 1.20 {@code DrawContext.draw()} call that the newer lines put before every raw-GL glass
 * draw (and that the code shared with those lines still calls).
 *
 * <p>On 1.16.5 GUI drawing is immediate: fills and blits go straight through the {@code Tessellator}, text is drawn
 * into its own {@code Immediate} and flushed on the spot, and a GUI item model flushes the shared entity consumers
 * itself. So normally there is nothing queued when a glass piece is drawn; the one buffer that CAN hold something is
 * vanilla's shared entity immediate ({@code getBufferBuilders().getEntityVertexConsumers()}), which this draws.
 * Safe to call anywhere on the render thread; never throws.
 */
public final class GuiFlush {

    private GuiFlush() {}

    private static boolean warned;

    /** Draw everything the GUI has buffered so far. */
    public static void flush() {
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc != null) mc.getBufferBuilders().getEntityVertexConsumers().draw();
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                System.out.println("[S1mp1e] GUI buffer flush failed: " + t);
            }
        }
    }
}
