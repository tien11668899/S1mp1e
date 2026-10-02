package dev.s1mp1e.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;

/**
 * The self-contained OpenGL program + full-screen quad that draws the S1mp1e brand intro. This is the 1.15.2
 * (compatibility-profile, GLSL 120) port of the intro slice of LiquidGlass26's {@code GlassPipeline}: 26.2 added a
 * dedicated {@code s1mp1e_intro} render pipeline and drew the intro as one deferred {@code GuiElementRenderState};
 * the 1.17.1 … 1.21.1 lines use a standalone core-profile program with its own VAO. Here it is the kit the rest of
 * this line's glass uses ({@link dev.s1mp1e.glass.render.GlassRenderer}): the shared GLSL-120 {@code glass.vsh}
 * (which reads the fixed-function built-ins {@code gl_Vertex / gl_MultiTexCoord0 / gl_Color} and
 * {@code gl_ModelViewProjectionMatrix}) + {@code s1mp1e_intro.fsh}, and one immediate-mode quad — no VAO, no matrix
 * uniforms.
 *
 * <p>It deliberately does NOT touch the shared {@link dev.s1mp1e.glass.render.GlassProgram}: those programs sample the
 * {@code SceneCapture} backdrop, whereas the intro is pure procedural output on black and samples only its own SDF
 * strip. Keeping it separate means the intro compiles and runs at boot — DURING the first resource reload, before the
 * mod's assets are registered as a resource pack — because both shaders are read straight off the CLASSPATH (the same
 * version-agnostic trick {@code GlassProgram} uses).
 *
 * <p>The quad carries (time, mode) on the texture coordinate and opacity on the vertex alpha; {@code ScreenSize}
 * (framebuffer px) and {@code TexSize} (the bound SDF strip's size in texels — GLSL 120 has no {@code texelFetch} /
 * {@code textureSize}, so the shader's header rebuilds the fetch from it) are plain uniforms set each draw.
 *
 * <p>GL state is handled exactly as {@code GlassRenderer} does: enables through {@link RenderSystem} (so its cache
 * stays right), the program bind and the vertex calls raw, the colour cache re-synced at the end.
 */
public final class IntroPipeline {

    private IntroPipeline() {}

    /** 0 = untried, 1 = ready, -1 = failed (never retry). */
    private static int state = 0;

    private static int program = 0;
    private static int uSampler0 = -1, uScreen = -1, uTexSize = -1;

    /** True once the intro program is compiled and linked. */
    public static boolean usable() { return state == 1 && program != 0; }

    /** Compile + link once. Returns false (permanently) if this GPU can't run it. */
    public static boolean ensureReady() {
        if (state != 0) return state == 1;
        try {
            if (!GL.getCapabilities().OpenGL20) { state = -1; return false; }
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
                System.out.println("[S1mp1e] s1mp1e_intro link failed: " + GL20.glGetProgramInfoLog(p));
                state = -1;
                return false;
            }
            program   = p;
            uSampler0 = GL20.glGetUniformLocation(p, "Sampler0");
            uScreen   = GL20.glGetUniformLocation(p, "ScreenSize");
            uTexSize  = GL20.glGetUniformLocation(p, "TexSize");
            state = 1;
            System.out.println("[S1mp1e] intro program ready");
            return true;
        } catch (Throwable t) {
            System.out.println("[S1mp1e] intro shader unavailable: " + t);
            state = -1;
            return false;
        }
    }

    /**
     * Draw one full-screen intro quad over the GUI rect {@code 0..w x 0..h} (GUI px). {@code t} is seconds since the
     * intro began, {@code mode} the shader mode (0 boot / 1 short / 2 loop) carried on the texture coordinate's y,
     * {@code opacity} the overall alpha (1 while playing, ramped down to fade out), {@code texGlId} the SDF strip's GL
     * id. Drawn under the current GL projection / model-view (the GUI ortho), immediately.
     */
    public static void draw(int w, int h, float t, float mode, float opacity, int texGlId) {
        if (!usable() || opacity <= 0f) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        float op = opacity < 0f ? 0f : (opacity > 1f ? 1f : opacity);

        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(770, 771, 1, 0); // SRC_ALPHA, ONE_MINUS_SRC_ALPHA, 1, 0
        RenderSystem.disableAlphaTest();
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);

        // Bind the SDF strip on unit 0 THROUGH RenderSystem (keeps its active-unit + texture caches in sync).
        RenderSystem.activeTexture(GL13.GL_TEXTURE0);
        RenderSystem.enableTexture();
        RenderSystem.bindTexture(texGlId);
        int tw = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
        int th = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);

        GL20.glUseProgram(program);
        if (uSampler0 >= 0) GL20.glUniform1i(uSampler0, 0);
        if (uScreen   >= 0) GL20.glUniform2f(uScreen, mc.getWindow().getFramebufferWidth(), mc.getWindow().getFramebufferHeight());
        if (uTexSize  >= 0) GL20.glUniform2f(uTexSize, Math.max(1, tw), Math.max(1, th));

        // One quad covering (0,0)-(w,h); texcoord = (t, mode) on every vertex, colour = white with alpha = opacity.
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glColor4f(1f, 1f, 1f, op);
        GL11.glTexCoord2f(t, mode); GL11.glVertex2f(0f, 0f);
        GL11.glTexCoord2f(t, mode); GL11.glVertex2f(0f, h);
        GL11.glTexCoord2f(t, mode); GL11.glVertex2f(w,  h);
        GL11.glTexCoord2f(t, mode); GL11.glVertex2f(w,  0f);
        GL11.glEnd();

        GL20.glUseProgram(0);

        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.enableAlphaTest();
        RenderSystem.bindTexture(0);
        // Raw glColor4f above bypassed the colour cache: force it to a value it cannot hold, then white.
        RenderSystem.color4f(0f, 0f, 0f, 0f);
        RenderSystem.color4f(1f, 1f, 1f, 1f);
        RenderSystem.enableDepthTest();
    }

    // ---- helpers (mirrors GlassProgram) -----------------------------------

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
