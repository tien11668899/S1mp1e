package dev.s1mp1e.glass.render;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.s1mp1e.client.gui.ScreenOpenFade;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.util.math.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.FloatBuffer;
import java.nio.charset.Charset;

/**
 * Menu-to-menu cross-dissolve: no screen switch outside gameplay cuts hard any more. The 1.17.1 (core-profile /
 * MatrixStack) port of LiquidGlass26's {@code ScreenTransition}.
 *
 * <p>Vanilla swaps screens in one frame: the old screen is gone on the very next frame, and the new one's vanilla text
 * and backgrounds appear at once while its glass buttons / sliders only start their 150 ms {@link ScreenOpenFade} — so
 * for a frame or two the new screen is a bare "skeleton" of floating labels. Here, on {@code MinecraftClient.setScreen},
 * the last finished frame (the outgoing screen, still held by the main framebuffer) is copied into a snapshot texture
 * and for {@link #DURATION_S} it is drawn over everything at a smoothstep-falling alpha, while {@link ScreenOpenFade}
 * is held at 1 so the incoming screen is complete underneath from its first frame: a true cross-dissolve with no gap
 * and no skeleton frame. A switch mid-dissolve re-grabs the current (blended) frame, so chains stay continuous.
 *
 * <p>26.2 drew the fading snapshot as a deferred GUI element through a {@code glass_fade} pipeline; this line has no
 * deferred pipeline API, so — like {@code IntroPipeline} — the snapshot is drawn immediately through a standalone
 * program ({@code glass.vsh} + {@code s1mp1e_dissolve.fsh}, both read off the classpath, so nothing depends on the
 * resource manager and the 26.2 "never initialise in setScreen" trap does not exist here).
 *
 * <p>Not applied to in-game screens that already have their own open/close motion: container screens (glass panel fade
 * + close ghost) and the chat input (its own fade).
 */
public final class ScreenDissolve {

    private ScreenDissolve() {}

    /** Dissolve length. */
    public static final float DURATION_S = 0.22f;

    private static long startNs;
    private static boolean active;

    // ---- snapshot texture ----
    private static int snapTex = 0, snapW = 0, snapH = 0;

    // ---- program ----
    /** 0 = untried, 1 = ready, -1 = failed (never retry). */
    private static int state = 0;
    private static int program = 0;
    private static int uProj = -1, uModelView = -1, uSampler0 = -1;
    private static final FloatBuffer MAT16 = BufferUtils.createFloatBuffer(16);
    private static final int FLOATS_PER_VERT = 8;             // pos.xy, uv.xy, rgba
    private static final int STRIDE_BYTES    = FLOATS_PER_VERT * 4;
    private static int vao = 0, vbo = 0;
    private static final FloatBuffer QUAD = BufferUtils.createFloatBuffer(6 * FLOATS_PER_VERT);

    // ---------------------------------------------------------------------------------------------------------------
    //  triggers
    // ---------------------------------------------------------------------------------------------------------------

    /** {@code MinecraftClient.setScreen} HEAD: {@code from} is still the current screen, {@code to} the incoming one. */
    public static void onSetScreen(Screen from, Screen to) {
        if (from == to) return;
        if (excluded(from) || excluded(to)) return;
        if (!begin()) return;
        ScreenOpenFade.holdUntil(startNs + (long) (DURATION_S * 1.0e9f));
    }

    /**
     * A content switch WITHIN one screen (CreateWorldScreen's tabs, the creative category, the advancement tab, the
     * recipe book toggle): only the content changes while the chrome stays put, so vanilla swaps it in one frame. The
     * whole outgoing frame is snapshot and dissolved over the incoming one exactly like a screen switch — the unchanged
     * chrome overlaps pixel for pixel so only the content visibly cross-fades. NOT held on {@link ScreenOpenFade}: the
     * screen instance is unchanged, so its glass widgets never restarted their open fade.
     */
    public static void onTabSwitch() {
        begin();
    }

    /** Whether a switch made right now would be cross-dissolved (program built, no resource reload running). */
    public static boolean canDissolve() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc != null && mc.getOverlay() == null && ensureReady();   // 1.20.1 has no isFinishedLoading(): the boot reload is an overlay too
    }

    private static boolean excluded(Screen s) {
        return s instanceof HandledScreen<?> || s instanceof ChatScreen;
    }

    private static boolean begin() {
        if (!RenderSystem.isOnRenderThread()) return false;
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
     * setScreen runs from a tick or an input callback, i.e. BETWEEN frames: the main framebuffer still holds the last
     * finished frame (it is only cleared when the next frame starts) but it is not necessarily the bound read buffer —
     * after the end-of-frame blit the default framebuffer is bound, whose back buffer is undefined after a swap. So the
     * main FBO is bound explicitly as the READ framebuffer for the copy and the previous binding restored.
     */
    private static boolean grabSnapshot() {
        MinecraftClient mc = MinecraftClient.getInstance();
        Framebuffer fb = mc.getFramebuffer();
        if (fb == null || fb.fbo <= 0) return false;
        int w = fb.textureWidth, h = fb.textureHeight;
        if (w <= 0 || h <= 0) return false;

        int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int prevRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);

        if (snapTex == 0 || snapW != w || snapH != h) {
            if (snapTex != 0) GL11.glDeleteTextures(snapTex);
            snapTex = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, snapTex);
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

        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fb.fbo);
        GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);

        // Raw binds bypass RenderSystem's texture cache: restore through it (bind 0 first defeats its equal-cache
        // short circuit), exactly as SceneCapture does.
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
        RenderSystem.bindTexture(0);
        RenderSystem.bindTexture(prevTex);
        return true;
    }

    // ---------------------------------------------------------------------------------------------------------------
    //  draw: the very last GUI draw of the frame (after toasts and the glass tooltip layer)
    // ---------------------------------------------------------------------------------------------------------------

    /** Draw the fading snapshot over everything drawn this frame. {@code ctx} is flushed first. */
    public static void draw(MatrixStack ctx) {
        if (!active) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        float t = (System.nanoTime() - startNs) / 1.0e9f / DURATION_S;
        Framebuffer fb = mc.getFramebuffer();
        if (t >= 1f || fb == null || fb.textureWidth != snapW || fb.textureHeight != snapH || state != 1
                || mc.getOverlay() != null) {   // a reload started: never draw under/over it
            end();
            return;
        }
        float s = t <= 0f ? 0f : t * t * (3f - 2f * t);   // smoothstep
        float alpha = 1f - s;
        if (alpha <= 1f / 255f) return;
        if (ctx != null) dev.s1mp1e.glass.render.GuiFlush.flush();   // everything deferred so far lands UNDER the snapshot

        int w = mc.getWindow().getScaledWidth(), h = mc.getWindow().getScaledHeight();

        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(770, 771, 1, 0); // SRC_ALPHA, ONE_MINUS_SRC_ALPHA, 1, 0
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);

        RenderSystem.activeTexture(GL13.GL_TEXTURE0);
        RenderSystem.bindTexture(snapTex);

        int prevProg = GL20.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        GL20.glUseProgram(program);
        setMat(uProj, RenderSystem.getProjectionMatrix());
        setMat(uModelView, RenderSystem.getModelViewStack().peek().getModel());
        if (uSampler0 >= 0) GL20.glUniform1i(uSampler0, 0);

        // Two triangles over (0,0)-(w,h). The framebuffer texture is y-up, GUI space y-down: top of screen = v 1.
        QUAD.clear();
        vert(0f, 0f, 0f, 1f, alpha);
        vert(0f, h,  0f, 0f, alpha);
        vert(w,  h,  1f, 0f, alpha);
        vert(0f, 0f, 0f, 1f, alpha);
        vert(w,  h,  1f, 0f, alpha);
        vert(w,  0f, 1f, 1f, alpha);
        QUAD.flip();

        if (vao == 0) { vao = GL30.glGenVertexArrays(); vbo = GL15.glGenBuffers(); }
        int prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int prevVbo = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, QUAD, GL15.GL_DYNAMIC_DRAW);
        GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, STRIDE_BYTES, 0L);
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, STRIDE_BYTES, 8L);
        GL20.glVertexAttribPointer(2, 4, GL11.GL_FLOAT, false, STRIDE_BYTES, 16L);
        GL20.glEnableVertexAttribArray(0);
        GL20.glEnableVertexAttribArray(1);
        GL20.glEnableVertexAttribArray(2);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, prevVbo);
        GL30.glBindVertexArray(prevVao);

        GL20.glUseProgram(prevProg);

        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.activeTexture(GL13.GL_TEXTURE0);
        RenderSystem.bindTexture(0);
        RenderSystem.setShaderColor(0f, 0f, 0f, 0f);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.enableDepthTest();
    }

    /** True while a snapshot is being dissolved (DevShot / diagnostics). */
    public static boolean active() { return active; }

    private static void end() {
        active = false;
        ScreenOpenFade.holdUntil(0L);
    }

    private static void vert(float x, float y, float u, float v, float a) {
        QUAD.put(x).put(y).put(u).put(v).put(1f).put(1f).put(1f).put(a);
    }

    // ---------------------------------------------------------------------------------------------------------------
    //  program (mirrors IntroPipeline)
    // ---------------------------------------------------------------------------------------------------------------

    private static boolean ensureReady() {
        if (state != 0) return state == 1;
        try {
            if (!GL.getCapabilities().OpenGL30) { state = -1; return false; }
            int vs = compile(GL20.GL_VERTEX_SHADER, read("shaders/glass.vsh"), "glass.vsh");
            if (vs == 0) { state = -1; return false; }
            int fs = compile(GL20.GL_FRAGMENT_SHADER, read("shaders/s1mp1e_dissolve.fsh"), "s1mp1e_dissolve.fsh");
            if (fs == 0) { GL20.glDeleteShader(vs); state = -1; return false; }
            int p = GL20.glCreateProgram();
            GL20.glAttachShader(p, vs);
            GL20.glAttachShader(p, fs);
            GL20.glBindAttribLocation(p, 0, "Position");
            GL20.glBindAttribLocation(p, 1, "UV0");
            GL20.glBindAttribLocation(p, 2, "Color");
            GL20.glLinkProgram(p);
            GL20.glDeleteShader(vs);
            GL20.glDeleteShader(fs);
            if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                System.out.println("[S1mp1e] s1mp1e_dissolve link failed: " + GL20.glGetProgramInfoLog(p));
                state = -1;
                return false;
            }
            program    = p;
            uProj      = GL20.glGetUniformLocation(p, "ProjMat");
            uModelView = GL20.glGetUniformLocation(p, "ModelViewMat");
            uSampler0  = GL20.glGetUniformLocation(p, "Sampler0");
            state = 1;
            System.out.println("[S1mp1e] dissolve program ready");
            return true;
        } catch (Throwable t) {
            System.out.println("[S1mp1e] dissolve shader unavailable: " + t);
            state = -1;
            return false;
        }
    }

    private static void setMat(int loc, Matrix4f m) {
        if (loc < 0) return;
        MAT16.clear();
        m.writeColumnMajor(MAT16);
        MAT16.rewind();
        GL20.glUniformMatrix4fv(loc, false, MAT16);
    }

    private static int compile(int type, String src, String label) {
        int id = GL20.glCreateShader(type);
        GL20.glShaderSource(id, src);
        GL20.glCompileShader(id);
        if (GL20.glGetShaderi(id, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            System.out.println("[S1mp1e] " + label + " compile failed: " + GL20.glGetShaderInfoLog(id));
            GL20.glDeleteShader(id);
            return 0;
        }
        return id;
    }

    private static String read(String path) throws Exception {
        InputStream in = ScreenDissolve.class.getResourceAsStream("/assets/s1mp1e/" + path);
        if (in == null) throw new java.io.FileNotFoundException("/assets/s1mp1e/" + path);
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(in, Charset.forName("UTF-8")));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } finally {
            in.close();
        }
    }
}
