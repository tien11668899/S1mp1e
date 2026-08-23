package dev.s1mp1e.glass.render;

import net.minecraft.client.MinecraftClient;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

/**
 * Replaces vanilla's tiled dirt background on world-less screens with the title
 * screen's own frame, blurred — the 1.8.9 equivalent of 26.2's native menu blur.
 *
 * <p>The source is the panorama ALONE — captured from an ASM hook the instant
 * {@code GuiMainMenu.renderSkybox} returns, before the logo, splash and buttons
 * are drawn. Grabbing the finished title frame instead would blur the title
 * artwork and ghost the buttons into the backdrop. Once you leave the main menu
 * nothing re-captures, so the panorama simply stays frozen behind the menus.
 */
public final class MenuBackdrop {

    /** Blur strength in physical px, and how far the result is darkened. */
    private static final float RADIUS = 14f;
    private static final float DIM    = 0.35f;

    private static int texture = 0;
    private static int texW = 0, texH = 0;
    private static boolean hasFrame = false;

    /** ~30 captures a second is far more than a slow panorama pan needs. */
    private static final long CAPTURE_GAP_NS = 33_000_000L;
    private static long lastCaptureNanos = 0L;

    private MenuBackdrop() {}

    /** True once a title-screen frame has been captured. */
    public static boolean ready() {
        return hasFrame && texture != 0
            && GlassProgram.ensureReady() && GlassProgram.blurUsable();
    }

    /**
     * Snapshot the panorama. Driven from an ASM hook placed immediately after
     * {@code GuiMainMenu.renderSkybox} returns, because that is the only point
     * where the title screen's BACKGROUND is on screen by itself — capturing at
     * end-of-frame would bake in the logo, splash text and buttons and blur
     * those too.
     */
    public static void capture() {
        // Throttled: the panorama pans slowly and this freezes the moment you
        // leave the menu anyway, so a full-screen copy every frame is waste.
        long now = System.nanoTime();
        if (now - lastCaptureNanos < CAPTURE_GAP_NS) return;
        lastCaptureNanos = now;

        MinecraftClient mc = MinecraftClient.getInstance();
        int w = mc.getWindow().getFramebufferWidth(), h = mc.getWindow().getFramebufferHeight();
        if (w <= 0 || h <= 0) return;

        int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        if (texture == 0 || texW != w || texH != h) {
            if (texture != 0) GL11.glDeleteTextures(texture);
            texture = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            // Level 0 must exist before glCopyTexSubImage2D, and the min filter
            // must not be a mipmap filter — an incomplete texture makes GL act
            // as if texturing were off, which renders flat white.
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGB, w, h, 0,
                              GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            texW = w; texH = h;
        } else {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        }
        GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
        // Resync RenderSystem's texture cache after the raw restore, else MC
        // samples our capture texture everywhere and the screen goes white.
        RenderSystem.bindTexture(0);
        RenderSystem.bindTexture(prevTex);
        hasFrame = true;
    }

    /** Draw the blurred backdrop full-screen. False -> caller draws the dirt. */
    public static boolean draw() {
        if (!ready()) return false;

        MinecraftClient mc = MinecraftClient.getInstance();
        float w = mc.getWindow().getScaledWidth();
        float h = mc.getWindow().getScaledHeight();

        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT
                        | GL11.GL_CURRENT_BIT | GL11.GL_TEXTURE_BIT);
        GL11.glDisable(GL11.GL_BLEND);          // opaque: it IS the background
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(false);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glColor4f(1f, 1f, 1f, 1f);

        GlassProgram.bind(GlassProgram.BLUR);
        GlassProgram.setBlur(RADIUS, DIM);

        // capture is framebuffer space (origin bottom-left); GUI space is
        // top-left, but the shader derives its UV from gl_FragCoord, so the
        // quad only needs to cover the screen.
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(0f, 1f); GL11.glVertex2f(0f, 0f);
        GL11.glTexCoord2f(0f, 0f); GL11.glVertex2f(0f, h);
        GL11.glTexCoord2f(1f, 0f); GL11.glVertex2f(w,  h);
        GL11.glTexCoord2f(1f, 1f); GL11.glVertex2f(w,  0f);
        GL11.glEnd();

        GlassProgram.unbind();
        GL11.glDepthMask(true);
        GL11.glColor4f(1f, 1f, 1f, 1f);
        GL11.glPopAttrib();
        RenderSystem.bindTexture(0);
        RenderSystem.color4f(1f, 1f, 1f, 1f);
        return true;
    }
}
