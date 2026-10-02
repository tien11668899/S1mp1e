package dev.s1mp1e.glass.render;

import com.mojang.blaze3d.platform.GLX;
import com.mojang.blaze3d.platform.GlStateManager; // TODO44 no such class in 1.14.4
import com.mojang.blaze3d.platform.GlStateManager;
import dev.s1mp1e.client.gui.ScreenOpenFade;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.ContainerScreen;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

/**
 * Menu-to-menu cross-dissolve: no screen switch outside gameplay cuts hard any more. The 1.14.4 (fixed-function /
 * MatrixStack) port of LiquidGlass26's {@code ScreenTransition}, as the 1.17.1 … 1.21.1 lines have it.
 *
 * <p>Vanilla swaps screens in one frame: the old screen is gone on the very next frame, and the new one's vanilla text
 * and backgrounds appear at once while its glass buttons / sliders only start their 150 ms {@link ScreenOpenFade} — so
 * for a frame or two the new screen is a bare "skeleton" of floating labels. Here, on {@code MinecraftClient.openScreen},
 * the last finished frame (the outgoing screen, still held by the main framebuffer) is copied into a snapshot texture
 * and for {@link #DURATION_S} it is drawn over everything at a smoothstep-falling alpha, while {@link ScreenOpenFade}
 * is held at 1 so the incoming screen is complete underneath from its first frame: a true cross-dissolve with no gap
 * and no skeleton frame. A switch mid-dissolve re-grabs the current (blended) frame, so chains stay continuous.
 *
 * <p><b>1.14.4 plumbing.</b> The core-profile lines draw the snapshot through a little program
 * ({@code s1mp1e_dissolve.fsh}: texel × vertex alpha) with their own VAO. This line is the compatibility profile,
 * where that is exactly what the fixed-function pipeline does with one textured quad under {@code GL_MODULATE} and
 * {@code glColor4f(1, 1, 1, alpha)} — the recipe of this line's old {@link ScreenFade}, kept as it is (raw GL inside
 * {@code glPushAttrib}, then an explicit re-sync of {@code GlStateManager}'s texture / colour caches). So there is no
 * shader and nothing to initialise: the 26.2 "never build a program inside setScreen" trap cannot occur.
 * What replaces {@link ScreenFade}: the snapshot is taken AT the switch from the main framebuffer (not from a 20 Hz
 * end-of-frame copy that could be 50 ms stale), the curve is a 220 ms smoothstep, the snapshot is the frame's top
 * layer (after the toasts and the tooltip card), and the incoming screen's glass is held complete underneath.
 *
 * <p>Not applied to in-game screens that already have their own open/close motion: container screens (glass panel fade
 * + close ghost) and the chat input (its own fade).
 */
public final class ScreenDissolve {

    private ScreenDissolve() {}

    /** Dissolve length. */
    public static final float DURATION_S = 0.22f;

    /** {@code GL_FRAMEBUFFER_BINDING} (the same enum in GL 3.0, ARB and EXT framebuffer objects). */
    private static final int GL_FRAMEBUFFER_BINDING = 0x8CA6;

    private static long startNs;
    private static boolean active;

    // ---- snapshot texture ----
    private static int snapTex = 0, snapW = 0, snapH = 0;

    // ---------------------------------------------------------------------------------------------------------------
    //  triggers
    // ---------------------------------------------------------------------------------------------------------------

    /** {@code MinecraftClient.openScreen} HEAD: {@code from} is still the current screen, {@code to} the incoming one. */
    public static void onSetScreen(Screen from, Screen to) {
        if (from == to) return;
        if (excluded(from) || excluded(to)) return;
        if (!begin()) return;
        ScreenOpenFade.holdUntil(startNs + (long) (DURATION_S * 1.0e9f));
    }

    /**
     * A content switch WITHIN one screen (the create-world "More World Options" toggle, the creative category, the
     * advancement tab): only the content changes while the chrome stays put, so vanilla swaps it in one frame. The
     * whole outgoing frame is snapshot and dissolved over the incoming one exactly like a screen switch — the unchanged
     * chrome overlaps pixel for pixel so only the content visibly cross-fades. NOT held on {@link ScreenOpenFade}: the
     * screen instance is unchanged, so its glass widgets never restarted their open fade.
     */
    public static void onTabSwitch() {
        begin();
    }

    /** Whether a switch made right now would be cross-dissolved (no resource reload / splash running). */
    public static boolean canDissolve() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc != null && mc.getOverlay() == null;
    }

    private static boolean excluded(Screen s) {
        return s instanceof ContainerScreen<?> || s instanceof ChatScreen;
    }

    private static boolean begin() {
        if (!MinecraftClient.getInstance().isOnThread()) return false;
        if (!canDissolve()) return false;
        if (!grabSnapshot()) return false;
        startNs = System.nanoTime();
        active = true;
        return true;
    }

    // ---------------------------------------------------------------------------------------------------------------
    //  snapshot: the last finished frame, read straight from MC's main framebuffer
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * openScreen runs from a tick or an input callback, i.e. BETWEEN frames: the main framebuffer still holds the last
     * finished frame (it is only cleared when the next frame starts) but it is not necessarily the bound one — after
     * the end-of-frame blit the default framebuffer is bound, whose back buffer is undefined after a swap. So the main
     * FBO is bound explicitly for the copy and the previous binding restored. ({@code GLX.glBindFramebuffer}
     * picks the GL 3.0 / ARB / EXT entry point this context has; vanilla 1.14.4 only ever binds read and draw
     * together, which is what gets restored.)
     */
    private static boolean grabSnapshot() {
        MinecraftClient mc = MinecraftClient.getInstance();
        Framebuffer fb = mc.getFramebuffer();
        if (fb == null || fb.fbo <= 0) return false;
        int w = fb.textureWidth, h = fb.textureHeight;
        if (w <= 0 || h <= 0) return false;

        int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int prevFbo = GL11.glGetInteger(GL_FRAMEBUFFER_BINDING);

        if (snapTex == 0 || snapW != w || snapH != h) {
            if (snapTex != 0) GL11.glDeleteTextures(snapTex);
            snapTex = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, snapTex);
            // Level 0 defined and a non-mipmap min filter BEFORE the copy: an incomplete texture samples as "texturing
            // off" and the quad would be a flat white flash (the bug this line's first ScreenFade had).
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGB, w, h, 0, GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE,
                    (java.nio.ByteBuffer) null);
            snapW = w; snapH = h;
        } else {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, snapTex);
        }

        if (prevFbo != fb.fbo) GLX.glBindFramebuffer(GLX.GL_FRAMEBUFFER, fb.fbo);
        GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h);
        if (prevFbo != fb.fbo) GLX.glBindFramebuffer(GLX.GL_FRAMEBUFFER, prevFbo);

        // Raw binds bypass GlStateManager's texture cache: restore through it (bind 0 first defeats its equal-cache
        // short circuit), exactly as SceneCapture does.
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
        GlStateManager.bindTexture(0);
        GlStateManager.bindTexture(prevTex);
        return true;
    }

    // ---------------------------------------------------------------------------------------------------------------
    //  draw: the very last GUI draw of the frame (after toasts and the glass tooltip layer)
    // ---------------------------------------------------------------------------------------------------------------

    /** Draw the fading snapshot over everything drawn this frame. */
    public static void draw() {
        if (!active) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        float t = (System.nanoTime() - startNs) / 1.0e9f / DURATION_S;
        Framebuffer fb = mc.getFramebuffer();
        if (t >= 1f || fb == null || fb.textureWidth != snapW || fb.textureHeight != snapH || snapTex == 0
                || mc.getOverlay() != null) {   // a reload started: never draw under/over it
            end();
            return;
        }
        float s = t <= 0f ? 0f : t * t * (3f - 2f * t);   // smoothstep
        float alpha = 1f - s;
        if (alpha <= 1f / 255f) return;
        GuiFlush.flush();   // everything queued so far lands UNDER the snapshot

        float w = mc.window.getScaledWidth(), h = mc.window.getScaledHeight();

        // Raw GL throughout, then an explicit re-sync (ScreenFade's recipe): mixing GlStateManager calls with
        // glPushAttrib / glPopAttrib leaves its cache believing state the pop has already reverted.
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT | GL11.GL_TEXTURE_BIT
                | GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glDepthMask(false);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        // MODULATE so glColor's alpha scales the texel (the snapshot is GL_RGB, i.e. alpha 1).
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL11.GL_MODULATE);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, snapTex);
        GL11.glColor4f(1f, 1f, 1f, alpha);

        // The framebuffer texture is y-up, GUI space y-down: top of screen = v 1.
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(0f, 1f); GL11.glVertex2f(0f, 0f);
        GL11.glTexCoord2f(0f, 0f); GL11.glVertex2f(0f, h);
        GL11.glTexCoord2f(1f, 0f); GL11.glVertex2f(w,  h);
        GL11.glTexCoord2f(1f, 1f); GL11.glVertex2f(w,  0f);
        GL11.glEnd();

        GL11.glColor4f(1f, 1f, 1f, 1f);
        GL11.glPopAttrib();
        GlStateManager.bindTexture(0);
        GlStateManager.color4f(0f, 0f, 0f, 0f);
        GlStateManager.color4f(1f, 1f, 1f, 1f);
    }

    /** True while a snapshot is being dissolved (DevShot / diagnostics). */
    public static boolean active() { return active; }

    private static void end() {
        active = false;
        ScreenOpenFade.holdUntil(0L);
    }
}
