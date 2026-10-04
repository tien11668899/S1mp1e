package dev.s1mp1e.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GLContext;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;

/**
 * The self-contained OpenGL program + full-screen quad that draws the S1mp1e brand intro (group 10) — the 1.12.2
 * (LWJGL 2, GLSL 120) port of mc1144's {@code IntroPipeline}: the shared {@code glass.vsh} (byte-identical across the
 * GLSL-120 lines) + {@code s1mp1e_intro.fsh} (copied unchanged from mc1144 — already GLSL 120 with the
 * {@code texelFetch} emulation), one immediate-mode quad carrying (time, mode) on the texture coordinate and opacity
 * on the vertex alpha; {@code ScreenSize} / {@code TexSize} uniforms. Separate from {@code GlassProgram} (the intro
 * never samples the scene backdrop). Shaders are read off the classpath. Render thread only.
 */
public final class IntroPipeline {

    private IntroPipeline() {}

    private static int state = 0;          // 0 untried, 1 ready, -1 failed
    private static int program = 0;
    private static int uSampler0 = -1, uScreen = -1, uTexSize = -1;

    public static boolean usable() { return state == 1 && program != 0; }

    public static boolean ensureReady() {
        if (state != 0) return state == 1;
        try {
            if (!GLContext.getCapabilities().OpenGL20) { state = -1; return false; }
            int vs = compile(GL20.GL_VERTEX_SHADER, read("shaders/glass.vsh"), "glass.vsh");
            if (vs == 0) { state = -1; return false; }
            int fs = compile(GL20.GL_FRAGMENT_SHADER, read("shaders/s1mp1e_intro.fsh"), "s1mp1e_intro.fsh");
            if (fs == 0) { GL20.glDeleteShader(vs); state = -1; return false; }
            int p = GL20.glCreateProgram();
            GL20.glAttachShader(p, vs);
            GL20.glAttachShader(p, fs);
            GL20.glLinkProgram(p);
            GL20.glDeleteShader(vs);
            GL20.glDeleteShader(fs);
            if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                System.out.println("[S1mp1e] s1mp1e_intro link failed: " + GL20.glGetProgramInfoLog(p, 4096));
                state = -1;
                return false;
            }
            program = p;
            uSampler0 = GL20.glGetUniformLocation(p, "Sampler0");
            uScreen = GL20.glGetUniformLocation(p, "ScreenSize");
            uTexSize = GL20.glGetUniformLocation(p, "TexSize");
            state = 1;
            System.out.println("[S1mp1e] intro program ready");
            return true;
        } catch (Throwable t) {
            System.out.println("[S1mp1e] intro shader unavailable: " + t);
            state = -1;
            return false;
        }
    }

    /** One full-screen intro quad over GUI rect 0..w x 0..h under the current GUI ortho. */
    public static void draw(int w, int h, float t, float mode, float opacity, int texGlId, int texW, int texH) {
        if (!usable() || opacity <= 0f) return;
        Minecraft mc = Minecraft.getMinecraft();
        float op = opacity > 1f ? 1f : opacity;

        GlStateManager.disableDepth();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.disableAlpha();
        GlStateManager.disableCull();
        GlStateManager.depthMask(false);
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        GlStateManager.enableTexture2D();
        GlStateManager.bindTexture(texGlId);

        GL20.glUseProgram(program);
        if (uSampler0 >= 0) GL20.glUniform1i(uSampler0, 0);
        if (uScreen >= 0) GL20.glUniform2f(uScreen, mc.displayWidth, mc.displayHeight);
        if (uTexSize >= 0) GL20.glUniform2f(uTexSize, Math.max(1, texW), Math.max(1, texH));

        // front-facing TL -> BL -> BR -> TR (the GUI pass culls back faces; cull is off here anyway)
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glColor4f(1f, 1f, 1f, op);
        GL11.glTexCoord2f(t, mode); GL11.glVertex2f(0f, 0f);
        GL11.glTexCoord2f(t, mode); GL11.glVertex2f(0f, h);
        GL11.glTexCoord2f(t, mode); GL11.glVertex2f(w, h);
        GL11.glTexCoord2f(t, mode); GL11.glVertex2f(w, 0f);
        GL11.glEnd();

        GL20.glUseProgram(0);
        GlStateManager.depthMask(true);
        GlStateManager.enableCull();
        GlStateManager.enableAlpha();
        GlStateManager.bindTexture(0);
        GlStateManager.color(0f, 0f, 0f, 0f);
        GlStateManager.color(1f, 1f, 1f, 1f);
        GlStateManager.enableDepth();
    }

    private static int compile(int type, String src, String label) {
        int id = GL20.glCreateShader(type);
        GL20.glShaderSource(id, src);
        GL20.glCompileShader(id);
        if (GL20.glGetShaderi(id, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            System.out.println("[S1mp1e] " + label + " compile failed: " + GL20.glGetShaderInfoLog(id, 4096));
            GL20.glDeleteShader(id);
            return 0;
        }
        return id;
    }

    private static String read(String path) throws Exception {
        InputStream in = IntroPipeline.class.getResourceAsStream("/assets/s1mp1e/" + path);
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
