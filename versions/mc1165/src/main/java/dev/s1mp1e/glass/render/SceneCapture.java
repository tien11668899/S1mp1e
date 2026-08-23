package dev.s1mp1e.glass.render;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.opengl.GL11;

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

    /** Base grab: capture the current framebuffer, time-deduplicated. */
    public static void grab() { grab(false); }

    /**
     * Copy the current framebuffer into the backdrop texture. Cheap enough to
     * call once a frame; reallocates only when the window resizes.
     *
     * <p><b>{@code force} — the nested-glass fix.</b> A liquid-glass element must
     * refract everything drawn BENEATH it this frame. The base layers (HUD hotbar,
     * the container/creative panel) grab the world/dimmed-world backdrop ONCE via the
     * time-deduplicated {@link #grab()} — the 3 ms guard collapses their repeat calls
     * so the base panel and its own lattice/hover share one world grab (and never
     * refract each other).
     *
     * <p>But a glass element that sits ON TOP of already-drawn GUI — the recipe book
     * over the open inventory, a tooltip over a container — must capture that GUI as
     * its backdrop, not the stale world grab the base layer left behind. The time
     * dedup would fold such a top-layer grab into the base one (they happen microseconds
     * apart), so the top layer would wrongly refract the world instead of the inventory
     * behind it. Those call sites pass {@code force = true} to bypass the dedup and
     * snapshot the framebuffer AS IT IS right now — world + container + slots + items —
     * so the top glass shows the inventory through it. No self-ghosting: the grab runs
     * BEFORE the top element draws itself, so the snapshot never contains it.
     */
    public static void grab(boolean force) {
        long now = System.nanoTime();
        if (!force && now - lastGrabNanos < MIN_GRAB_GAP_NS) return;
        lastGrabNanos = now;

        MinecraftClient mc = MinecraftClient.getInstance();
        int w = mc.getWindow().getFramebufferWidth(), h = mc.getWindow().getFramebufferHeight();
        if (w <= 0 || h <= 0) return;

        int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);

        if (texture == 0 || texW != w || texH != h) {
            if (texture != 0) GL11.glDeleteTextures(texture);
            texture = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            // CLAMP_TO_EDGE: refraction near the frame border must not wrap
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGB, w, h, 0,
                              GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
            texW = w; texH = h;
        } else {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        }

        GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h);
        // 1.16.5: the raw binds above bypass RenderSystem's texture-unit cache.
        // Restore through RenderSystem so its cache matches actual GL again —
        // bind 0 first to defeat its no-op-on-equal-cache short circuit, else
        // MC keeps sampling our backdrop texture and the whole screen goes white.
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
        RenderSystem.bindTexture(0);
        RenderSystem.bindTexture(prevTex);
    }
}
