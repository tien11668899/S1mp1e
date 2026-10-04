package dev.s1mp1e.glass.render;

import dev.s1mp1e.client.gui.ScreenOpenFade;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.shader.Framebuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

/**
 * Menu-to-menu cross-dissolve (group 5): no screen switch outside gameplay cuts hard. The 1.12.2 port of
 * {@code versions/mc1144}'s {@code ScreenDissolve} (itself LiquidGlass26's {@code ScreenTransition}); it replaces this
 * line's older {@code ScreenFade} (a 150 ms linear fade from a 20 Hz end-of-frame copy that could be 50 ms stale).
 *
 * <p>On a screen switch the last finished frame — still held by the main framebuffer, because
 * {@code displayGuiScreen} runs between frames from a tick / input callback — is copied into a snapshot texture, and
 * for {@link #DURATION_S} it is drawn over everything at a smoothstep-falling alpha while {@link ScreenOpenFade} is
 * held at 1, so the incoming screen is complete underneath from its first frame: a true cross-dissolve, no skeleton
 * frame. A switch mid-dissolve re-grabs the current (blended) frame, so chains stay continuous.
 *
 * <p><b>1.12.2 plumbing.</b> Trigger: Forge's {@code GuiOpenEvent} (posted at the head of
 * {@code Minecraft.displayGuiScreen} while {@code currentScreen} is still the outgoing screen — the same instant as
 * 1.14.4's {@code openScreen} HEAD). Draw: {@code GlassTopLayer} at {@code RenderTickEvent} END, which in 1.12.2's
 * game loop comes after {@code updateCameraAndRender} (screen + tooltip) AND after {@code toastGui.drawToast}, so the
 * snapshot is the frame's top layer. Fixed-function: one textured quad under {@code GL_MODULATE} with
 * {@code glColor4f(1,1,1,a)}, raw GL inside {@code glPushAttrib} then a GlStateManager re-sync — no shader, so the
 * "never build a glass program inside setScreen" trap cannot occur (nothing here touches {@code GlassProgram}).
 *
 * <p>Not applied to screens with their own open/close motion: containers (glass panel fade + close ghost) and the
 * chat input.
 */
public final class ScreenDissolve {

    private ScreenDissolve() {}

    public static final float DURATION_S = 0.22f;

    private static final int GL_FRAMEBUFFER_BINDING = 0x8CA6;

    private static long startNs;
    private static boolean active;
    private static int snapTex = 0, snapW = 0, snapH = 0;

    /** Dev only (DevShot): the alpha the snapshot was drawn with on the last frame (-1 = none). */
    public static float lastAlpha = -1f;

    /** displayGuiScreen HEAD (GuiOpenEvent): {@code from} is still current, {@code to} the incoming screen. */
    public static void onSetScreen(GuiScreen from, GuiScreen to) {
        if (from == to) return;
        if (excluded(from) || excluded(to)) return;
        if (from == null && Minecraft.getMinecraft().world == null) return;   // boot: nothing finished yet to dissolve
        if (!begin()) return;
        ScreenOpenFade.holdUntil(startNs + (long) (DURATION_S * 1.0e9f));
    }

    /** A content switch within one screen (creative category, advancement tab, More World Options). */
    public static void onTabSwitch() {
        begin();
    }

    public static boolean canDissolve() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc != null && OpenGlHelper.isFramebufferEnabled() && mc.getFramebuffer() != null;
    }

    private static boolean excluded(GuiScreen s) {
        return s instanceof GuiContainer || s instanceof GuiChat;
    }

    private static boolean begin() {
        Minecraft mc = Minecraft.getMinecraft();
        if (!mc.isCallingFromMinecraftThread()) return false;
        if (!canDissolve()) return false;
        if (!grabSnapshot()) return false;
        startNs = System.nanoTime();
        active = true;
        return true;
    }

    /** Copy the main FBO (the last finished frame) into the snapshot; the previous FBO / texture are restored. */
    private static boolean grabSnapshot() {
        Minecraft mc = Minecraft.getMinecraft();
        Framebuffer fb = mc.getFramebuffer();
        if (fb == null || fb.framebufferObject <= 0) return false;
        int w = fb.framebufferTextureWidth, h = fb.framebufferTextureHeight;
        if (w <= 0 || h <= 0) return false;

        int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int prevFbo = GL11.glGetInteger(GL_FRAMEBUFFER_BINDING);

        if (snapTex == 0 || snapW != w || snapH != h) {
            if (snapTex != 0) GL11.glDeleteTextures(snapTex);
            snapTex = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, snapTex);
            // level 0 defined + a non-mipmap filter BEFORE the copy (an incomplete texture samples as "texturing off" =
            // the flat white flash this line's first ScreenFade had)
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

        if (prevFbo != fb.framebufferObject) OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, fb.framebufferObject);
        GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h);
        if (prevFbo != fb.framebufferObject) OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, prevFbo);

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
        GlStateManager.bindTexture(0);
        GlStateManager.bindTexture(prevTex);
        return true;
    }

    /** Draw the fading snapshot over everything drawn this frame (GlassTopLayer, GUI ortho already set up). */
    public static void draw() {
        if (!active) { lastAlpha = -1f; return; }
        Minecraft mc = Minecraft.getMinecraft();
        float t = (System.nanoTime() - startNs) / 1.0e9f / DURATION_S;
        Framebuffer fb = mc.getFramebuffer();
        if (t >= 1f || fb == null || fb.framebufferTextureWidth != snapW || fb.framebufferTextureHeight != snapH
                || snapTex == 0) {
            end();
            return;
        }
        float s = t <= 0f ? 0f : t * t * (3f - 2f * t);   // smoothstep
        float alpha = 1f - s;
        lastAlpha = alpha;
        if (alpha <= 1f / 255f) return;

        ScaledResolution sr = new ScaledResolution(mc);
        float w = sr.getScaledWidth(), h = sr.getScaledHeight();
        // fb texture is the whole (possibly larger) FBO; the frame occupies framebufferWidth x framebufferHeight
        float u1 = fb.framebufferWidth / (float) snapW, v1 = fb.framebufferHeight / (float) snapH;

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
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL11.GL_MODULATE);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, snapTex);
        GL11.glColor4f(1f, 1f, 1f, alpha);
        GL11.glBegin(GL11.GL_QUADS);                       // y-up texture, y-down GUI: top of screen = v1
        GL11.glTexCoord2f(0f, v1); GL11.glVertex2f(0f, 0f);
        GL11.glTexCoord2f(0f, 0f); GL11.glVertex2f(0f, h);
        GL11.glTexCoord2f(u1, 0f); GL11.glVertex2f(w, h);
        GL11.glTexCoord2f(u1, v1); GL11.glVertex2f(w, 0f);
        GL11.glEnd();
        GL11.glColor4f(1f, 1f, 1f, 1f);
        GL11.glPopAttrib();
        GlStateManager.bindTexture(0);
        GlStateManager.color(0f, 0f, 0f, 0f);
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    public static boolean active() { return active; }

    private static void end() {
        active = false;
        lastAlpha = -1f;
        ScreenOpenFade.holdUntil(0L);
    }
}
