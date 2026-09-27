package dev.s1mp1e.glass.render;

import com.mojang.blaze3d.systems.RenderSystem;
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
 * <p>The copy uses {@code glCopyTexSubImage2D} — a core GL 3.2 function that
 * still works under 1.17.1's forward-compatible core profile — from whatever
 * framebuffer is bound. During {@code InGameHud.render} / a screen's render that
 * is MC's main FBO ({@code mc.getFramebuffer()}, whose colour texture id is
 * {@code mc.getFramebuffer().getColorAttachment()}); we copy OUT of it into our
 * own texture rather than sampling the attachment directly, because sampling the
 * buffer you are rendering into is a feedback loop. This is the one class of the
 * three that is fully core-legal: no {@code glBegin}/{@code glPushAttrib}/fixed
 * function here, only texture object calls + the copy, so it is KEPT for 1.17.1.
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
     * duplicates so a secondary site (tooltip / recipe book) reuses whatever the
     * primary panel just grabbed instead of re-copying (and, worse, capturing
     * the primary's own glass into the backdrop → self-ghosting).
     *
     * <p>Use this for SECONDARY/reuse sites. Frame-primary sites that must own a
     * deterministic backdrop every frame (the HUD hotbar, each screen's panel)
     * call {@link #grabNow()} instead — the time guard silently skips a grab
     * whenever the previous frame ran long enough ago (i.e. at the high frame
     * rates of a paused screen), which left those surfaces sampling a stale
     * backdrop from a different render stage → flicker and dark edges.
     */
    public static void grab() {
        long now = System.nanoTime();
        if (now - lastGrabNanos < MIN_GRAB_GAP_NS) return;
        doGrab();
    }

    /**
     * Force a fresh framebuffer copy NOW, bypassing the {@link #grab()} time
     * guard. For frame-primary backdrops that must be deterministic every frame
     * regardless of frame rate — the HUD hotbar (world) and each screen's own
     * panel (world + blur + dim). Also refreshes the guard timestamp so a
     * following secondary {@link #grab()} within 3 ms still folds onto this copy.
     */
    public static void grabNow() {
        doGrab();
    }

    private static void doGrab() {
        lastGrabNanos = System.nanoTime();

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
            // CLAMP_TO_EDGE: refraction near the frame border must not wrap.
            // Core profile removed the legacy GL_CLAMP enum (it raises
            // GL_INVALID_ENUM under 1.17.1's forward-compatible core context);
            // GL_CLAMP_TO_EDGE is the core-legal spelling and what this always
            // meant — matching MenuBackdrop/ScreenFade which already use it.
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGB, w, h, 0,
                              GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
            texW = w; texH = h;
        } else {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        }

        GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h);
        // 1.16.5/1.17.1: the raw binds above bypass RenderSystem's texture-unit
        // cache. Restore through RenderSystem so its cache matches actual GL —
        // bind 0 first to defeat its no-op-on-equal-cache short circuit, else
        // MC keeps sampling our backdrop texture and the whole screen goes white.
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
        RenderSystem.bindTexture(0);
        RenderSystem.bindTexture(prevTex);
    }
}
