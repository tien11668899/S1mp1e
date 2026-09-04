package dev.s1mp1e.glass.render;

import net.minecraft.client.Minecraft;
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
 *
 * <h3>Why de-duplication is by FRAME, not by wall clock</h3>
 * This used to collapse grabs with a 3 ms {@code System.nanoTime()} guard, to
 * stop the inventory doing three full-screen copies per frame. That guard was
 * the container flicker: the call sites do not all want the SAME picture.
 * <ul>
 *   <li>the HUD grabs at Pre(ALL) — world only,</li>
 *   <li>the container grabs at BackgroundDrawn — world + the 0xC0 dim + the HUD,</li>
 *   <li>the tooltip grabs last — the complete GUI.</li>
 * </ul>
 * With a shared time guard, whichever site happened to be more than 3 ms after
 * the previous one won the copy — and that depended on {@code nanoTime} jitter,
 * not on render logic. A fast HUD pass meant the container's grab was skipped
 * and the panel refracted the BRIGHT undimmed world; a slow one (GC, a chunk
 * rebuild, an extra HUD module) let it through and the panel refracted the
 * ~75 % darker dimmed image. Alternating between those two backdrops from frame
 * to frame is exactly the flicker.
 *
 * <p>So: sites that merely need <em>a</em> backdrop call {@link #grabOnce()} and
 * share one copy per frame; sites that need <em>their own</em> point in the draw
 * order call {@link #forceGrab()}. Correctness costs up to three copies in a
 * frame with a tooltip open, which is what the old guard was trying to avoid —
 * but a stale backdrop is a visible bug and a copy is not.
 */
public final class SceneCapture {

    private static int texture = 0;
    private static int texW = 0, texH = 0;

    /** Bumped once per rendered frame by {@link #newFrame()}. */
    private static long frameId = 0L;
    /** The frame in which the texture was last filled; -1 = never. */
    private static long lastGrabFrame = -1L;

    private SceneCapture() {}

    public static int texture() { return texture; }

    /**
     * True when the backdrop holds something captured in the CURRENT frame.
     *
     * <p>This used to be {@code texture != 0}, which is permanently true after
     * the first capture — so every "is the backdrop fresh?" guard built on it
     * was dead code and glass could sample a frame-old picture.
     */
    public static boolean hasBackdrop() {
        return texture != 0 && lastGrabFrame == frameId;
    }

    /**
     * Open a new frame. Called once, as early in the overlay pass as we can get
     * (Pre(ALL) at HIGHEST priority), before any grab in that frame.
     */
    public static void newFrame() {
        frameId++;
    }

    /** Capture only if nothing has captured yet this frame. */
    public static void grabOnce() {
        if (lastGrabFrame == frameId) return;
        forceGrab();
    }

    /**
     * Capture unconditionally — for a call site whose position in the draw order
     * IS the point (the container wants the dimmed frame, the tooltip wants the
     * finished GUI).
     */
    public static void forceGrab() {
        Minecraft mc = Minecraft.getMinecraft();
        int w = mc.displayWidth, h = mc.displayHeight;
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
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
        lastGrabFrame = frameId;
    }

    /**
     * Back-compat alias for the old call shape. Prefer {@link #grabOnce()} or
     * {@link #forceGrab()} so the intent is explicit at the call site.
     */
    public static void grab() {
        grabOnce();
    }
}
