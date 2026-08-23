package dev.s1mp1e.glass.render;

import dev.s1mp1e.glass.anim.Fade;
import net.minecraft.client.MinecraftClient;
import com.mojang.blaze3d.platform.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

/**
 * Cross-dissolve between GUI screens.
 *
 * <p><b>Why the capture happens at end-of-frame and not on the screen change.</b>
 * The first version grabbed the framebuffer from {@code GuiOpenEvent}. That
 * event fires during tick/input handling, <em>outside</em> the render pass —
 * Minecraft has already finished the previous frame, unbound its FBO and
 * swapped — so {@code glCopyTexSubImage2D} silently failed and the texture was
 * never filled. An allocated-but-unfilled texture is not texture-complete, and
 * OpenGL then behaves <em>as if texturing were disabled</em>, which makes the
 * quad render the flat current colour: {@code glColor4f(1,1,1,alpha)}. That is
 * precisely the white flash that faded out.
 *
 * <p>So the frame is now captured at the END of every rendered frame, while the
 * colour buffer is valid. When the screen changes we already hold last frame's
 * image, and capturing simply pauses ({@link #holding}) until the dissolve
 * finishes — which also stops the fade from being captured into its own
 * snapshot and smearing.
 */
public final class ScreenFade {

    /** Same 150 ms every other fade in the mod uses. */
    public static final float FADE_MS = 150f;

    private static final Fade fade = new Fade(0f, FADE_MS);

    private static int  texture = 0;
    private static int  texW = 0, texH = 0;
    /** True while a dissolve is running: the snapshot is frozen. */
    private static boolean holding = false;

    /** ~20 captures a second is plenty for a 150 ms dissolve. */
    private static final long CAPTURE_INTERVAL_NS = 50_000_000L;
    private static long lastCaptureNanos = 0L;

    private ScreenFade() {}

    /**
     * Screen changed — dissolve out the frame captured at the end of the last
     * one. No capture happens here on purpose (see the class note).
     */
    public static void trigger() {
        if (texture == 0) return;   // nothing captured yet: hard cut, no flash
        holding = true;
        fade.snap(1f);
        fade.to(0f);
    }

    /** Draw the outgoing frame over the incoming screen. No-op when idle. */
    public static void draw() {
        if (!holding) return;
        float a = fade.value();
        if (a <= 0.004f || texture == 0) {
            holding = false;        // dissolve finished, resume capturing
            return;
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        float w = mc.window.getScaledWidth();
        float h = mc.window.getScaledHeight();

        // Raw GL throughout, then an explicit GlStateManager re-sync: mixing
        // GlStateManager calls with glPushAttrib/glPopAttrib leaves its cache
        // believing state that the pop has already reverted, and the next
        // frame's cached no-op then draws untextured — white again.
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT
                        | GL11.GL_CURRENT_BIT | GL11.GL_TEXTURE_BIT);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(false);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        // MODULATE so glColor's alpha scales the texel; REPLACE would ignore it
        // and blit the frame fully opaque.
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL11.GL_MODULATE);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glColor4f(1f, 1f, 1f, a);

        // Capture is framebuffer space (origin bottom-left), GUI space is
        // top-left, so V is flipped.
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(0f, 1f); GL11.glVertex2f(0f, 0f);
        GL11.glTexCoord2f(0f, 0f); GL11.glVertex2f(0f, h);
        GL11.glTexCoord2f(1f, 0f); GL11.glVertex2f(w,  h);
        GL11.glTexCoord2f(1f, 1f); GL11.glVertex2f(w,  0f);
        GL11.glEnd();

        GL11.glDepthMask(true);
        GL11.glColor4f(1f, 1f, 1f, 1f);
        GL11.glPopAttrib();
        GlStateManager.bindTexture(0);
        GlStateManager.color4f(1f, 1f, 1f, 1f);
    }

    /**
     * Copy the composited frame. Call at the END of every rendered frame, from
     * inside the render pass, where the colour buffer is guaranteed valid —
     * that is the whole point. Frozen while a dissolve is in flight.
     */
    public static void captureFrame() {
        if (holding) return;
        // THROTTLED. This copy exists only so a snapshot is ready the instant a
        // screen changes, but at 1080p it is ~6 MB of GPU copy and it was
        // running every single frame — a real cost for something shown for
        // 150 ms at falling opacity. Capturing ~20x a second leaves the
        // snapshot at most 50 ms stale, which is invisible in a dissolve.
        //
        // (Half-resolution would be the other lever, but glCopyTexSubImage2D
        // does not scale — a half-size copy grabs a quarter of the screen, not
        // a downscaled frame. Scaling would need glBlitFramebuffer and a
        // destination FBO.)
        long now = System.nanoTime();
        if (now - lastCaptureNanos < CAPTURE_INTERVAL_NS) return;
        lastCaptureNanos = now;

        MinecraftClient mc = MinecraftClient.getInstance();
        int w = mc.window.getFramebufferWidth(), h = mc.window.getFramebufferHeight();
        if (w <= 0 || h <= 0) return;

        int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        if (texture == 0 || texW != w || texH != h) {
            if (texture != 0) GL11.glDeleteTextures(texture);
            texture = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            // Level 0 MUST be defined before any glCopyTexSubImage2D, and the
            // min filter must not be a mipmap filter — otherwise the texture is
            // incomplete and GL silently disables texturing (the white bug).
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
    }
}
