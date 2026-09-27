package dev.s1mp1e.glass.render;

import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

/**
 * Owns the backdrop texture the glass shader samples.
 *
 * <p>LiquidGlass26 grabs at {@code GuiRenderer.render()} HEAD — the moment the
 * world is drawn but no GUI is — because sampling a backdrop that already
 * contains our own glass produces self-ghosting. The 1.8.9 equivalent is the
 * same instant: {@link net.minecraftforge.client.event.RenderGameOverlayEvent}
 * Pre(ALL) for the HUD, and just before a screen's background for GUIs.
 *
 * <p>The copy uses {@code glCopyTexSubImage2D} from whatever framebuffer is
 * bound, which works with and without MC's FBO path.
 */
public final class SceneCapture {

    private static int texture = 0;
    private static int texW = 0, texH = 0;

    /** Shorter than one frame at 300 fps, so it only ever folds duplicates. */
    private static final long MIN_GRAB_GAP_NS = 3_000_000L;
    private static long lastGrabNanos = 0L;

    private SceneCapture() {}

    public static int texture() { return texture; }

    /** True once a backdrop has been captured this frame. */
    public static boolean hasBackdrop() { return texture != 0; }

    /**
     * Copy the current framebuffer into the backdrop texture, DE-DUPLICATED.
     *
     * <p>Several call sites each want a fresh backdrop — the container panel,
     * the tooltip, the item-name popup — and with a tooltip up in the inventory
     * that was THREE full-screen copies in one frame. A 3 ms guard collapses
     * duplicates so a secondary site reuses whatever the primary panel just
     * grabbed instead of re-copying. Frame-primary sites that must own a
     * deterministic backdrop every frame (the config screen, HudGlass, the knob
     * lens) call {@link #grabNow()} instead.
     */
    public static void grab() {
        long now = System.nanoTime();
        if (now - lastGrabNanos < MIN_GRAB_GAP_NS) return;
        doGrab();
    }

    /**
     * Force a fresh framebuffer copy NOW, bypassing the {@link #grab()} time
     * guard. For frame-primary backdrops that must be deterministic every frame
     * regardless of frame rate — the config screen, HudGlass and the switch/knob
     * lens (un-deduplicated copy, matching mc1211). Also refreshes the guard
     * timestamp so a following secondary {@link #grab()} within 3 ms still folds
     * onto this copy.
     */
    public static void grabNow() {
        doGrab();
    }

    /**
     * Back-compat alias for the mc189-derived call shape: an unconditional grab.
     * Same as {@link #grabNow()}.
     */
    public static void forceGrab() {
        doGrab();
    }

    private static void doGrab() {
        lastGrabNanos = System.nanoTime();

        MinecraftClient mc = MinecraftClient.getInstance();
        int w = mc.window.getFramebufferWidth(), h = mc.window.getFramebufferHeight();
        if (w <= 0 || h <= 0) return;

        int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);

        if (texture == 0 || texW != w || texH != h) {
            if (texture != 0) GL11.glDeleteTextures(texture);
            texture = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            // CLAMP_TO_EDGE: refraction near the frame border must not wrap, and
            // GL_CLAMP with LINEAR would bleed the black border colour into the
            // edge (checklist B-2). GL_CLAMP_TO_EDGE is what this always meant.
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGB, w, h, 0,
                              GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
            texW = w; texH = h;
        } else {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        }

        GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h);
        // The raw binds above bypass GlStateManager's texture-unit cache. Restore
        // through GlStateManager so its cache matches actual GL again — bind 0
        // first to defeat its no-op-on-equal-cache short circuit, else MC keeps
        // sampling our backdrop texture and the whole screen goes white.
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
        GlStateManager.bindTexture(0);
        GlStateManager.bindTexture(prevTex);
    }
}
