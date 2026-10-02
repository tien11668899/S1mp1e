package dev.s1mp1e.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
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
 * The self-contained OpenGL program + full-screen quad that draws the S1mp1e brand intro. This is the 1.21.1
 * (core-profile / MatrixStack) port of the intro slice of LiquidGlass26's {@code GlassPipeline}: 26.2 added a
 * dedicated {@code s1mp1e_intro} render pipeline and drew the intro as one deferred {@code GuiElementRenderState};
 * this version cannot register a pipeline (the deferred GUI render-state API does not exist pre-26), so — like the
 * rest of this line's glass — the intro is a standalone raw-GL program that draws immediately.
 *
 * <p>It deliberately does NOT touch the shared {@link dev.s1mp1e.glass.render.GlassProgram}/
 * {@link dev.s1mp1e.glass.render.GlassRenderer}: those own the four glass programs (which sample the
 * {@code SceneCapture} backdrop), whereas the intro is pure procedural output on black and samples only its own SDF
 * strip. Keeping it separate means the intro compiles and runs at boot — DURING the first resource reload, before the
 * mod's assets are registered as a resource pack — because both shaders are read straight off the CLASSPATH (the same
 * version-agnostic trick {@code GlassProgram} uses). 26.2 needed a {@code ShaderManager} mixin to serve its shaders
 * during that first reload; this line needs none.
 *
 * <p>Reuses the shared {@code assets/s1mp1e/shaders/glass.vsh} vertex shader verbatim (it already emits
 * {@code vLocal = UV0} and {@code vColor = Color}, exactly what the intro fragment shader reads) and pairs it with
 * {@code assets/s1mp1e/shaders/s1mp1e_intro.fsh}. The quad carries (time, mode) on UV0 and opacity on the vertex
 * alpha; {@code ScreenSize} (framebuffer px) is a plain uniform set each bind, replacing 26.2's {@code Globals} UBO.
 *
 * <p>All GL state is saved and restored exactly as {@link dev.s1mp1e.glass.render.GlassRenderer} does — the
 * core-profile rule that any standalone draw inside MC's GUI pass must save+restore the VAO/VBO/program/texture unit
 * (never bind 0) or MC's own draws in the same frame corrupt.
 */
public final class IntroPipeline {

    private IntroPipeline() {}

    /** 0 = untried, 1 = ready, -1 = failed (never retry). */
    private static int state = 0;

    private static int program = 0;
    private static int uProj = -1, uModelView = -1, uSampler0 = -1, uScreen = -1;

    /** Reused 16-float scratch for streaming a {@link Matrix4f} into a mat4 uniform. */
    private static final FloatBuffer MAT16 = BufferUtils.createFloatBuffer(16);

    // ---- geometry (core profile: VAO + VBO + glDrawArrays), mirroring GlassRenderer ----
    private static final int FLOATS_PER_VERT = 8;             // pos.xy, uv.xy, rgba
    private static final int STRIDE_BYTES    = FLOATS_PER_VERT * 4;
    private static int vao = 0, vbo = 0;
    private static final FloatBuffer QUAD = BufferUtils.createFloatBuffer(6 * FLOATS_PER_VERT);

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
            // Same 0/1/2 attribute binding GlassProgram uses; glass.vsh is shared, so it must match.
            GL20.glBindAttribLocation(p, 0, "Position");
            GL20.glBindAttribLocation(p, 1, "UV0");
            GL20.glBindAttribLocation(p, 2, "Color");
            GL20.glLinkProgram(p);
            GL20.glDeleteShader(vs);
            GL20.glDeleteShader(fs);
            if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                System.out.println("[S1mp1e] s1mp1e_intro link failed: " + GL20.glGetProgramInfoLog(p));
                state = -1;
                return false;
            }
            program    = p;
            uProj      = GL20.glGetUniformLocation(p, "ProjMat");
            uModelView = GL20.glGetUniformLocation(p, "ModelViewMat");
            uSampler0  = GL20.glGetUniformLocation(p, "Sampler0");
            uScreen    = GL20.glGetUniformLocation(p, "ScreenSize");
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
     * intro began, {@code mode} the shader mode (0 boot / 1 short / 2 loop) carried on UV0.y, {@code opacity} the
     * overall alpha (1 while playing, ramped down to fade out), {@code texGlId} the bound SDF strip's GL id.
     *
     * <p>The caller must have flushed any pending {@code MatrixStack} batch ({@code context.draw()}) first, so this
     * immediate-GL quad lands ON TOP of the deferred draws rather than under a not-yet-flushed veil.
     */
    public static void draw(int w, int h, float t, float mode, float opacity, int texGlId) {
        if (!usable() || opacity <= 0f) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        float op = opacity < 0f ? 0f : (opacity > 1f ? 1f : opacity);

        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(770, 771, 1, 0); // SRC_ALPHA, ONE_MINUS_SRC_ALPHA, 1, 0
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);

        // Bind the SDF strip on unit 0 THROUGH RenderSystem (keeps its active-unit + texture caches in sync).
        RenderSystem.activeTexture(GL13.GL_TEXTURE0);
        RenderSystem.bindTexture(texGlId);

        // Use our program; save+restore MC's so its own GUI draws this frame don't run with a foreign program.
        int prevProg = GL20.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        GL20.glUseProgram(program);
        setMat(uProj, RenderSystem.getProjectionMatrix());
        setMat(uModelView, RenderSystem.getModelViewStack().peek().getPositionMatrix());   // a Mojang MatrixStack
        if (uSampler0 >= 0) GL20.glUniform1i(uSampler0, 0);
        if (uScreen   >= 0) GL20.glUniform2f(uScreen, mc.getWindow().getFramebufferWidth(), mc.getWindow().getFramebufferHeight());

        // Two triangles covering (0,0)-(w,h); UV = (t, mode) on every vertex, colour = white with alpha = opacity.
        QUAD.clear();
        quadVert(0f, 0f, t, mode, op);
        quadVert(0f, h,  t, mode, op);
        quadVert(w,  h,  t, mode, op);
        quadVert(0f, 0f, t, mode, op);
        quadVert(w,  h,  t, mode, op);
        quadVert(w,  0f, t, mode, op);
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
        // Leave shader colour WHITE so the first vanilla draw after us isn't tinted (cache-defeat with a 0 set).
        RenderSystem.setShaderColor(0f, 0f, 0f, 0f);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.enableDepthTest();
    }

    private static void quadVert(float x, float y, float u, float v, float a) {
        QUAD.put(x).put(y).put(u).put(v).put(1f).put(1f).put(1f).put(a);
    }

    // ---- helpers (mirrors GlassProgram) -----------------------------------

    private static void setMat(int loc, Matrix4f m) {
        if (loc < 0) return;
        MAT16.clear();
        m.writeColumnMajor(MAT16);   // 1.19.2: Mojang's own Matrix4f
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
