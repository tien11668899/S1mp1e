package dev.s1mp1e.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.Window;

/**
 * 1.18.2 stand-in for the {@code DrawableHelper.enableScissor(int,int,int,int)} /
 * {@code disableScissor()} statics that 1.19.2 adds and 1.18.2 lacks.
 *
 * <p>The config screen clips its scrolling module list to a rectangle. On 1.19.2 that is
 * {@code DrawableHelper.enableScissor(x0,y0,x1,y1)}; on 1.18.2 those two statics do not exist
 * (javap-confirmed they are the ONLY members 1.19.2's {@code DrawableHelper} adds over 1.18.2's),
 * so this class replicates their bytecode directly over the primitives that 1.18.2 does expose:
 * {@link Window#getFramebufferHeight()} + {@link Window#getScaleFactor()} to map scaled-GUI
 * coordinates into framebuffer pixels, then {@link RenderSystem#enableScissor(int,int,int,int)} /
 * {@link RenderSystem#disableScissor()} (both present on 1.18.2).
 *
 * <p>Single level, sequential, never nested — the config screen enables a rect, draws, then
 * disables, exactly as 1.19.2's {@code DrawableHelper} statics are used. Both calls are wrapped
 * so a failure degrades to unclipped drawing instead of crashing the screen; every call site
 * releases in a finally. Render thread only.
 */
public final class Scissor {

    private Scissor() {}

    /**
     * Clip subsequent draws to the scaled-GUI rectangle (x0, y0)-(x1, y1). Mirrors 1.19.2's
     * {@code DrawableHelper.enableScissor}: the framebuffer y is measured from the bottom, so the
     * top edge y0 is unused in the origin computation (it feeds the height only), matching vanilla.
     */
    public static void enable(int x0, int y0, int x1, int y1) {
        try {
            Window w = MinecraftClient.getInstance().getWindow();
            int fbH = w.getFramebufferHeight();
            double s = w.getScaleFactor();
            int sx = (int) (x0 * s);
            int sy = (int) (fbH - y1 * s);
            int sw = Math.max(0, (int) ((x1 - x0) * s));
            int sh = Math.max(0, (int) ((y1 - y0) * s));
            RenderSystem.enableScissor(sx, sy, sw, sh);
        } catch (Throwable t) {
            // Degrade to unclipped drawing rather than take the screen down.
            System.out.println("[S1mp1e] Scissor.enable failed, drawing unclipped: " + t);
        }
    }

    /** Release the clip set by {@link #enable}. Safe to call even if enable failed. */
    public static void disable() {
        try {
            RenderSystem.disableScissor();
        } catch (Throwable t) {
            System.out.println("[S1mp1e] Scissor.disable failed: " + t);
        }
    }
}
