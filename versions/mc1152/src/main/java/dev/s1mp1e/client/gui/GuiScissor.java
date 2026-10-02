package dev.s1mp1e.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.Window;

/**
 * 1.15.2 replacement for {@code DrawableHelper.enableScissor}/{@code disableScissor}, which do not exist until
 * 1.19 (the code shared with the newer lines calls this). It converts a scaled-GUI rectangle (top-left
 * {@code (x1,y1)}, bottom-right {@code (x2,y2)}) into a framebuffer-pixel {@code glScissor} box and enables clipping
 * through {@link RenderSystem}.
 *
 * <p>The conversion mirrors 1.19.2's {@code DrawableHelper.enableScissor} bytecode exactly: the framebuffer
 * height and scale factor come from the {@link Window}, the box origin is bottom-left, and every double is
 * narrowed with a plain {@code (int)} truncation (d2i), NOT {@link Math#round} — so list rows clip on the same
 * pixel boundary the vanilla 1.19+ helper would produce. The width/height are floored at 0.
 *
 * <p>{@code glScissor} is in WINDOW pixels and ignores the model-view: the rectangle is absolute GUI space whatever
 * translate is active (the same rule as {@code GlassWidgets.beginScissor} on this line). Single level, no stack.
 * Render thread only.
 */
public final class GuiScissor {

    private GuiScissor() {}

    /**
     * Enable clipping to the scaled-GUI rectangle from top-left {@code (x1,y1)} to bottom-right {@code (x2,y2)}.
     * Always pair with {@link #disable()}.
     */
    public static void enable(int x1, int y1, int x2, int y2) {
        Window w = MinecraftClient.getInstance().getWindow();
        int    fh = w.getFramebufferHeight();
        double s  = w.getScaleFactor();
        org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST);
        org.lwjgl.opengl.GL11.glScissor(
                (int) (x1 * s),
                (int) (fh - y2 * s),
                Math.max(0, (int) ((x2 - x1) * s)),
                Math.max(0, (int) ((y2 - y1) * s)));
    }

    public static void disable() {
        org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST);
    }
}
